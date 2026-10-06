package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.DocumentPreviewContent
import com.example.data.repository.DocumentRepository
import com.example.engine.SearchResult
import kotlinx.coroutines.launch
import java.util.Locale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.example.engine.KeywordHighlighter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPreviewSheet(
    result: SearchResult,
    repository: DocumentRepository,
    searchQuery: String = "",
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var previewContent by remember { mutableStateOf<DocumentPreviewContent?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var currentPdfPage by remember { mutableIntStateOf(0) }
    var pdfBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Text, 1: Rendered Pages
    var selectedKeywordFilter by remember { mutableStateOf<String?>(null) }

    var documentTags by remember(result) { mutableStateOf(result.tags) }
    var isAddingTag by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }

    val ext = result.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val isPdf = ext == "PDF"

    LaunchedEffect(result) {
        isLoading = true
        val content = repository.loadDocumentPreview(result.fileUri, result.fileName)
        previewContent = content
        if (content.isPdf && content.pdfPageCount > 0) {
            val bmp = repository.renderPdfPage(result.fileUri, 0)
            pdfBitmap = bmp
        }
        val tagsFromDb = repository.getTagsForFile(result.fileUri)
        if (tagsFromDb.isNotEmpty()) {
            documentTags = tagsFromDb
        }
        isLoading = false
    }

    LaunchedEffect(currentPdfPage) {
        if (isPdf && previewContent?.pdfPageCount ?: 0 > 0) {
            pdfBitmap = repository.renderPdfPage(result.fileUri, currentPdfPage)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag("document_preview_sheet"),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = result.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "Format: $ext • Matched Chunk #${result.chunkIndex + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { openExternalDocument(context, result.fileUri) },
                        modifier = Modifier.testTag("preview_open_external_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Launch,
                            contentDescription = "Open externally",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("preview_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive Document Categories & Tags Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Label,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Document Tags:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { isAddingTag = !isAddingTag },
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("preview_toggle_add_tag")
                        ) {
                            Icon(
                                imageVector = if (isAddingTag) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = "Add tag",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(documentTags) { tag ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        repository.removeTagFromDocument(result.fileUri, tag)
                                        documentTags = documentTags.filter { it != tag }
                                        Toast.makeText(context, "Removed tag #$tag", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove tag",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }

                        if (documentTags.isEmpty() && !isAddingTag) {
                            item {
                                Text(
                                    text = "No tags assigned yet. Tap + to categorize.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    if (isAddingTag) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = newTagText,
                                onValueChange = { newTagText = it },
                                placeholder = { Text("Enter tag (e.g. Work, Finance)", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("preview_tag_input_field"),
                                textStyle = MaterialTheme.typography.bodySmall
                            )

                            FilledTonalButton(
                                onClick = {
                                    val clean = newTagText.trim()
                                    if (clean.isNotBlank()) {
                                        scope.launch {
                                            repository.addTagToDocument(result.fileUri, clean)
                                            if (!documentTags.contains(clean)) {
                                                documentTags = documentTags + clean
                                            }
                                            newTagText = ""
                                            isAddingTag = false
                                            Toast.makeText(context, "Added tag #$clean", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("preview_save_tag_button"),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // PDF Tabs (Text View vs Rendered Pages)
            if (isPdf && (previewContent?.pdfPageCount ?: 0) > 0) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .padding(bottom = 12.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Extracted Text & Matches") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Rendered Pages (${previewContent?.pdfPageCount})") }
                    )
                }
            }

            // Main Content Area
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (previewContent?.isImage == true) {
                // Image Preview with EXIF Metadata
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    val imgBmp = previewContent?.imageBitmap
                    if (imgBmp != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                        ) {
                            Image(
                                bitmap = imgBmp.asImageBitmap(),
                                contentDescription = result.fileName,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Extracted Image & EXIF Metadata:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val rawImageText = previewContent?.fullText ?: result.chunkText
                    val imageKeywords = remember(result, searchQuery) {
                        KeywordHighlighter.extractKeywords(result.highlightedTerms, searchQuery, rawImageText)
                    }
                    val imageHighlightResult = remember(rawImageText, imageKeywords, selectedKeywordFilter) {
                        KeywordHighlighter.highlightText(
                            text = rawImageText,
                            keywords = imageKeywords,
                            query = searchQuery,
                            filterKeyword = selectedKeywordFilter,
                            highlightColor = Color(0xFF0284C7),
                            highlightBgColor = Color(0xFF38BDF8).copy(alpha = 0.25f),
                            baseTextColor = Color.Unspecified
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = imageHighlightResult.annotatedString,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else if (isPdf && selectedTab == 1) {
                // PDF Page Viewer
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Page Controller Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (currentPdfPage > 0) currentPdfPage--
                            },
                            enabled = currentPdfPage > 0
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous page"
                            )
                        }

                        Text(
                            text = "Page ${currentPdfPage + 1} of ${previewContent?.pdfPageCount ?: 1}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )

                        IconButton(
                            onClick = {
                                val max = (previewContent?.pdfPageCount ?: 1) - 1
                                if (currentPdfPage < max) currentPdfPage++
                            },
                            enabled = currentPdfPage < ((previewContent?.pdfPageCount ?: 1) - 1)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next page"
                            )
                        }
                    }

                    // Rendered PDF Page Bitmap
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        val bmp = pdfBitmap
                        if (bmp != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "PDF Page ${currentPdfPage + 1}",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            } else {
                // Text View (with highlighted matches)
                val fullText = previewContent?.fullText.orEmpty().ifBlank { result.chunkText }
                val scrollState = rememberScrollState()

                val effectiveKeywords = remember(result, searchQuery) {
                    KeywordHighlighter.extractKeywords(result.highlightedTerms, searchQuery, fullText)
                }

                val primaryColor = MaterialTheme.colorScheme.primary
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface

                val fullTextHighlightResult = remember(fullText, effectiveKeywords, selectedKeywordFilter, primaryColor, onSurfaceColor) {
                    KeywordHighlighter.highlightText(
                        text = fullText,
                        keywords = effectiveKeywords,
                        query = searchQuery,
                        filterKeyword = selectedKeywordFilter,
                        highlightColor = primaryColor,
                        highlightBgColor = primaryColor.copy(alpha = 0.22f),
                        baseTextColor = onSurfaceColor
                    )
                }

                val chunkHighlightResult = remember(result.chunkText, effectiveKeywords, selectedKeywordFilter, primaryColor, onSurfaceColor) {
                    KeywordHighlighter.highlightText(
                        text = result.chunkText,
                        keywords = effectiveKeywords,
                        query = searchQuery,
                        filterKeyword = selectedKeywordFilter,
                        highlightColor = primaryColor,
                        highlightBgColor = primaryColor.copy(alpha = 0.25f),
                        baseTextColor = onSurfaceColor
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    // Keyword Matches Filter/Summary Bar
                    if (effectiveKeywords.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⚡ Matching Keywords (${fullTextHighlightResult.totalMatchesCount} matches)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (selectedKeywordFilter != null) {
                                        Text(
                                            text = "Show All",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable { selectedKeywordFilter = null }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        FilterChip(
                                            selected = selectedKeywordFilter == null,
                                            onClick = { selectedKeywordFilter = null },
                                            label = { Text("All (${fullTextHighlightResult.totalMatchesCount})", fontSize = 11.sp) }
                                        )
                                    }
                                    items(effectiveKeywords) { kw ->
                                        val count = fullTextHighlightResult.keywordFrequencies[kw] ?: 0
                                        val isSelected = selectedKeywordFilter.equals(kw, ignoreCase = true)
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                selectedKeywordFilter = if (isSelected) null else kw
                                            },
                                            label = {
                                                Text(
                                                    text = if (count > 0) "$kw ($count)" else kw,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Matched Chunk Callout Box with Keyword Highlights
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Relevant Search Match (Cosine: %.2f • Rank #${result.vectorRank ?: 1})".format(result.cosineSimilarity),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = chunkHighlightResult.annotatedString,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 20.sp
                            )
                        }
                    }

                    Text(
                        text = "Full Document Text:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    // Scrollable full text viewer with precise search highlight
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = fullTextHighlightResult.annotatedString,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp,
                                fontFamily = if (ext in listOf("JSON", "XML", "CSV")) FontFamily.Monospace else FontFamily.Default
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

private fun openExternalDocument(context: Context, uriString: String) {
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
