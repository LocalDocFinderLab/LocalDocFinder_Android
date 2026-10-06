package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.DocumentDetail
import com.example.data.repository.DocumentRepository
import com.example.engine.KeywordHighlighter
import com.example.engine.SearchResult
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only, expandable bottom sheet shown when a search result is tapped.
 *
 * Opens half-height with the file's metadata and the start of its content; drag it up or use the
 * expand button for the full-height reader. Nothing in the sheet edits the document or its tags —
 * the content can be selected and copied, and the original file can be opened in another app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailSheet(
    result: SearchResult,
    repository: DocumentRepository,
    searchQuery: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val isExpanded = sheetState.targetValue == SheetValue.Expanded

    var detail by remember(result.fileUri) { mutableStateOf<DocumentDetail?>(null) }
    var isLoading by remember(result.fileUri) { mutableStateOf(true) }
    var loadFailed by remember(result.fileUri) { mutableStateOf(false) }

    LaunchedEffect(result.fileUri) {
        isLoading = true
        loadFailed = false
        try {
            detail = repository.loadDocumentDetail(result.fileUri, result.fileName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        }
        isLoading = false
    }

    val listState = rememberLazyListState()
    // Bring the matched chunk into view once the content is loaded (items 0 and 1 are metadata + section header).
    LaunchedEffect(detail) {
        val loaded = detail ?: return@LaunchedEffect
        val matchedPosition = loaded.chunks.indexOfFirst { it.chunkIndex == result.chunkIndex }
        if (matchedPosition > 0 && searchQuery.isNotBlank()) {
            listState.animateScrollToItem(index = matchedPosition + 2)
        }
    }

    val ext = result.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val badgeColor = getFileTypeBadgeColor(ext)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag("document_detail_sheet"),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // Half height while collapsed so the lower part of the content is never hidden off-screen;
        // grows to a near full-screen reader when expanded.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (isExpanded) 0.94f else 0.5f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (ext.isNotEmpty() && ext.length <= 4) ext else "DOC",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Read-only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            if (isExpanded) sheetState.partialExpand() else sheetState.expand()
                        }
                    },
                    modifier = Modifier.testTag("detail_toggle_expand_button")
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                        contentDescription = if (isExpanded) "Collapse" else "Expand to full screen",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { openExternally(context, result.fileUri) },
                    modifier = Modifier.testTag("detail_open_external_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Launch,
                        contentDescription = "Open in another app",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("detail_close_button")
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            val loaded = detail
            when {
                isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("detail_loading_indicator"),
                        strokeWidth = 3.dp
                    )
                }

                loadFailed || loaded == null || loaded.chunks.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "The indexed content of this document is not available. Re-index it to read it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("detail_content_list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "metadata") {
                        DocumentMetadataCard(result = result, detail = loaded, searchQuery = searchQuery)
                    }
                    item(key = "content_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Document content",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${loaded.chunks.size} section(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    itemsIndexed(
                        items = loaded.chunks,
                        key = { _, chunk -> "chunk_${chunk.chunkIndex}" }
                    ) { _, chunk ->
                        val isMatch = chunk.chunkIndex == result.chunkIndex && searchQuery.isNotBlank()
                        DocumentContentBlock(
                            text = chunk.text,
                            label = "Section ${chunk.chunkIndex + 1}" + (chunk.pageLabel?.let { " · $it" } ?: ""),
                            isMatch = isMatch,
                            terms = result.highlightedTerms,
                            query = searchQuery
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentMetadataCard(
    result: SearchResult,
    detail: DocumentDetail,
    searchQuery: String
) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val ext = result.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail_metadata_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            MetadataRow("Type", buildString {
                append(if (ext.isNotEmpty()) ext else "Unknown")
                detail.mimeType?.let { append(" • $it") }
            })
            MetadataRow(
                "Size",
                detail.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
                    ?: "Unavailable (source file not accessible)"
            )
            MetadataRow("Location", detail.location)
            detail.sourceModifiedMillis?.let {
                MetadataRow("Modified", dateFormat.format(Date(it)))
            }
            if (detail.indexedAtMillis > 0L) {
                MetadataRow("Indexed", dateFormat.format(Date(detail.indexedAtMillis)))
            }
            MetadataRow(
                "Content",
                "${detail.wordCount} words • ${detail.totalCharacters} characters • ${detail.chunks.size} sections"
            )
            if (searchQuery.isNotBlank()) {
                MetadataRow("Matched section", "#${result.chunkIndex + 1} of ${detail.chunks.size}")
                if (result.cosineSimilarity > 0f || result.bm25Score > 0f) {
                    MetadataRow(
                        "Relevance",
                        buildString {
                            if (result.cosineSimilarity > 0f) append("Semantic ${(result.cosineSimilarity * 100).toInt()}%")
                            if (result.bm25Score > 0f) {
                                if (isNotEmpty()) append(" • ")
                                append("Keyword %.2f".format(Locale.US, result.bm25Score))
                            }
                        }
                    )
                }
            }

            if (detail.tags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        detail.tags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = "#$tag",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DocumentContentBlock(
    text: String,
    label: String,
    isMatch: Boolean,
    terms: List<String>,
    query: String
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    val highlightBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val baseColor = MaterialTheme.colorScheme.onSurface
    val annotated = remember(text, terms, query, highlightColor, baseColor) {
        KeywordHighlighter.highlightText(
            text = text,
            keywords = terms,
            query = query,
            highlightColor = highlightColor,
            highlightBgColor = highlightBg,
            baseTextColor = baseColor
        ).annotatedString
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isMatch) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (isMatch) "$label • best match" else label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp
                )
            }
        }
    }
}

private fun openExternally(context: Context, uriString: String) {
    try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No external handler found for this document", Toast.LENGTH_SHORT).show()
    }
}
