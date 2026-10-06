package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.DocumentPreviewContent
import com.example.data.repository.DocumentRepository
import com.example.engine.SearchResult
import kotlinx.coroutines.launch
import java.util.Locale

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.example.engine.KeywordHighlighter

/**
 * GlossyDocumentPreviewBottomSheet
 *
 * A sleek, translucent bottom-sheet component that triggers when any search result is selected.
 * Displays comprehensive document content preview, extracted text, keyword highlights,
 * PDF rendered pages, and intuitive Previous / Next search result navigation directly
 * within the glossy overlay UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossyDocumentPreviewBottomSheet(
    result: SearchResult,
    resultsList: List<SearchResult>,
    onSelectResult: (SearchResult) -> Unit,
    repository: DocumentRepository,
    glassOpacity: Float = 0.65f,
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
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Text & Highlights, 1: Rendered Pages
    var selectedKeywordFilter by remember { mutableStateOf<String?>(null) }

    var documentTags by remember(result) { mutableStateOf(result.tags) }
    var isAddingTag by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }

    val ext = result.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val isPdf = ext == "PDF"

    // Current index in results list for sequential navigation
    val currentIndex = remember(result, resultsList) {
        val idx = resultsList.indexOfFirst { it.chunkId == result.chunkId }
        if (idx >= 0) idx else 0
    }
    val hasPrevious = currentIndex > 0
    val hasNext = currentIndex < resultsList.size - 1

    LaunchedEffect(result) {
        isLoading = true
        currentPdfPage = 0
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

    LaunchedEffect(currentPdfPage, result) {
        if (isPdf && (previewContent?.pdfPageCount ?: 0) > 0) {
            pdfBitmap = repository.renderPdfPage(result.fileUri, currentPdfPage)
        }
    }

    val matchPercent = (result.cosineSimilarity * 100).toInt().coerceIn(1, 100)
    val scoreBadgeColor = when {
        matchPercent >= 80 -> Color(0xFF34D399) // Emerald
        matchPercent >= 55 -> Color(0xFF38BDF8) // Cyan
        matchPercent >= 40 -> Color(0xFFFBBF24) // Amber
        else -> Color(0xFF94A3B8)
    }

    val sheetBgColor = Color(0xFF0F172A).copy(alpha = (glassOpacity * 0.95f).coerceIn(0.35f, 0.98f))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.testTag("glossy_document_preview_bottom_sheet"),
        containerColor = sheetBgColor,
        scrimColor = Color(0xFF060913).copy(alpha = (glassOpacity * 0.70f).coerceIn(0.2f, 0.9f)),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF818CF8).copy(alpha = 0.5f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 18.dp)
        ) {
            // Header Bar: Emblem, File Details, Match Pill, Navigation & Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Emblem / Icon badge
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E293B).copy(alpha = 0.8f))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_localdoc_symbol),
                            contentDescription = "LocalDoc Finder",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = result.fileName,
                            color = Color(0xFFF8FAFC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = ".${ext.ifEmpty { "DOC" }} • Chunk #${result.chunkIndex + 1}",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            // Similarity match pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(scoreBadgeColor.copy(alpha = 0.2f))
                                    .border(1.dp, scoreBadgeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$matchPercent% Match",
                                    color = scoreBadgeColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Action buttons: Open Externally & Dismiss
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { openExternalDoc(context, result.fileUri) },
                        modifier = Modifier.testTag("preview_open_external")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Launch,
                            contentDescription = "Open file",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("preview_close_sheet")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive Document Categories & Tags Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.75f),
                border = BorderStroke(1.dp, Color(0xFF334155)),
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
                                tint = Color(0xFF38BDF8)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Document Tags:",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { isAddingTag = !isAddingTag },
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("glossy_preview_toggle_add_tag")
                        ) {
                            Icon(
                                imageVector = if (isAddingTag) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = "Add tag",
                                tint = Color(0xFF38BDF8),
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
                                color = Color(0xFF6366F1).copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.5f)),
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
                                        color = Color(0xFFA5B4FC),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove tag",
                                        tint = Color(0xFFA5B4FC),
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }

                        if (documentTags.isEmpty() && !isAddingTag) {
                            item {
                                Text(
                                    text = "No tags assigned yet. Tap + to categorize.",
                                    color = Color(0xFF64748B),
                                    fontSize = 11.sp
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
                                placeholder = { Text("Enter tag (e.g. Work, Research)", fontSize = 11.sp, color = Color(0xFF64748B)) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("glossy_preview_tag_input"),
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFF1F5F9), fontSize = 12.sp)
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
                                modifier = Modifier.testTag("glossy_preview_save_tag_button"),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFF38BDF8).copy(alpha = 0.25f),
                                    contentColor = Color(0xFF38BDF8)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Navigation Bar: Previous (←) / Next (→) Results Selector
            if (resultsList.size > 1) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (hasPrevious) {
                                    onSelectResult(resultsList[currentIndex - 1])
                                }
                            },
                            enabled = hasPrevious,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("preview_nav_previous")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous result",
                                tint = if (hasPrevious) Color(0xFF38BDF8) else Color(0xFF475569),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Text(
                            text = "Result ${currentIndex + 1} of ${resultsList.size}",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )

                        IconButton(
                            onClick = {
                                if (hasNext) {
                                    onSelectResult(resultsList[currentIndex + 1])
                                }
                            },
                            enabled = hasNext,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("preview_nav_next")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next result",
                                tint = if (hasNext) Color(0xFF38BDF8) else Color(0xFF475569),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            // PDF Tabs (Text vs Rendered Page view)
            if (isPdf && (previewContent?.pdfPageCount ?: 0) > 0) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF1E293B).copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .padding(bottom = 10.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                "Extracted Text & Matches",
                                fontSize = 11.sp,
                                color = if (selectedTab == 0) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                "Rendered Pages (${previewContent?.pdfPageCount})",
                                fontSize = 11.sp,
                                color = if (selectedTab == 1) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                            )
                        }
                    )
                }
            }

            // Content Area
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Color(0xFF38BDF8),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Loading document preview…", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            } else if (isPdf && selectedTab == 1) {
                // PDF Rendered Page Viewer
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
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (currentPdfPage > 0) currentPdfPage-- },
                            enabled = currentPdfPage > 0,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous page",
                                tint = if (currentPdfPage > 0) Color(0xFF38BDF8) else Color(0xFF475569)
                            )
                        }

                        Text(
                            text = "Page ${currentPdfPage + 1} of ${previewContent?.pdfPageCount ?: 1}",
                            color = Color(0xFFF1F5F9),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )

                        IconButton(
                            onClick = {
                                val max = (previewContent?.pdfPageCount ?: 1) - 1
                                if (currentPdfPage < max) currentPdfPage++
                            },
                            enabled = currentPdfPage < ((previewContent?.pdfPageCount ?: 1) - 1),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next page",
                                tint = if (currentPdfPage < ((previewContent?.pdfPageCount ?: 1) - 1)) Color(0xFF38BDF8) else Color(0xFF475569)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp)),
                        color = Color(0xFF1E293B).copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, Color(0xFF334155))
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
                                CircularProgressIndicator(color = Color(0xFF38BDF8))
                            }
                        }
                    }
                }
            } else if (previewContent?.isImage == true) {
                // Image Preview with EXIF data
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    val imgBmp = previewContent?.imageBitmap
                    if (imgBmp != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF1E293B).copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        ) {
                            Image(
                                bitmap = imgBmp.asImageBitmap(),
                                contentDescription = result.fileName,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Extracted Image & EXIF Metadata:",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
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
                            highlightColor = Color(0xFF38BDF8),
                            highlightBgColor = Color(0xFF38BDF8).copy(alpha = 0.28f),
                            baseTextColor = Color(0xFFE2E8F0)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = imageHighlightResult.annotatedString,
                            fontSize = 12.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            } else {
                // Text View with Matched Snippet and Highlighted Words
                val fullText = previewContent?.fullText.orEmpty().ifBlank { result.chunkText }
                val scrollState = rememberScrollState()

                val effectiveKeywords = remember(result, searchQuery) {
                    KeywordHighlighter.extractKeywords(result.highlightedTerms, searchQuery, fullText)
                }

                val fullTextHighlightResult = remember(fullText, effectiveKeywords, selectedKeywordFilter) {
                    KeywordHighlighter.highlightText(
                        text = fullText,
                        keywords = effectiveKeywords,
                        query = searchQuery,
                        filterKeyword = selectedKeywordFilter,
                        highlightColor = Color(0xFF38BDF8),
                        highlightBgColor = Color(0xFF38BDF8).copy(alpha = 0.28f),
                        baseTextColor = Color(0xFFE2E8F0)
                    )
                }

                val chunkHighlightResult = remember(result.chunkText, effectiveKeywords, selectedKeywordFilter) {
                    KeywordHighlighter.highlightText(
                        text = result.chunkText,
                        keywords = effectiveKeywords,
                        query = searchQuery,
                        filterKeyword = selectedKeywordFilter,
                        highlightColor = Color(0xFF38BDF8),
                        highlightBgColor = Color(0xFF38BDF8).copy(alpha = 0.32f),
                        baseTextColor = Color(0xFFF1F5F9)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    // Matching Keywords Filter/Summary Bar
                    if (effectiveKeywords.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E293B).copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
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
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (selectedKeywordFilter != null) {
                                        Text(
                                            text = "Show All",
                                            color = Color(0xFFA5B4FC),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
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
                                            label = { Text("All (${fullTextHighlightResult.totalMatchesCount})", fontSize = 10.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Color(0xFF38BDF8).copy(alpha = 0.3f),
                                                selectedLabelColor = Color(0xFF38BDF8)
                                            )
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
                                                    fontSize = 10.sp
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Color(0xFF6366F1).copy(alpha = 0.35f),
                                                selectedLabelColor = Color(0xFFA5B4FC)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Relevant Search Match Callout Box with Keyword Highlights
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.45f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⚡ Matched Neural Chunk (Rank #${result.vectorRank ?: 1} • Cosine: %.2f)".format(result.cosineSimilarity),
                                    color = Color(0xFF818CF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Matched Chunk", result.chunkText))
                                        Toast.makeText(context, "Snippet copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy snippet",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = chunkHighlightResult.annotatedString,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Text(
                        text = "Full Document Text:",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Scrollable full text viewer with precise keyword highlights
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.6f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = fullTextHighlightResult.annotatedString,
                                fontSize = 12.sp,
                                lineHeight = 20.sp,
                                fontFamily = if (ext in listOf("JSON", "XML", "CSV", "YAML", "KT", "JAVA", "PY")) FontFamily.Monospace else FontFamily.Default
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Actions Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF475569))
                ) {
                    Text("Close", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                }

                FilledTonalButton(
                    onClick = { openExternalDoc(context, result.fileUri) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF38BDF8).copy(alpha = 0.25f),
                        contentColor = Color(0xFF38BDF8)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Launch,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open File", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun openExternalDoc(context: Context, uriString: String) {
    try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Cannot open file directly. Location: $uriString", Toast.LENGTH_SHORT).show()
    }
}
