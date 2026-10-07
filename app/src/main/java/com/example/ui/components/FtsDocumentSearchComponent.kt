package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.SearchHistoryEntity
import com.example.engine.DateRangePreset
import com.example.engine.SearchMode
import com.example.engine.SearchResult
import com.example.engine.formatDateRangeLabel

import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Modular Jetpack Compose UI component for document search powered by SQLite FTS & Vector Search.
 *
 * Provides:
 * 1. [FtsSearchTextField] - A search bar with SQLite FTS query indicator, history launcher, and clear actions.
 * 2. [FtsSearchResultsList] - A list area displaying matching document results, highlighted keywords, FTS rank, and actions.
 */
@Composable
fun FtsDocumentSearchComponent(
    query: String,
    onQueryChanged: (String) -> Unit,
    searchMode: SearchMode,
    onSearchModeChanged: (SearchMode) -> Unit,
    results: List<SearchResult>,
    rawResults: List<SearchResult>,
    isSearching: Boolean,
    latencyMs: Long,
    totalFiles: Int,
    totalChunks: Int,
    selectedTag: String? = null,
    allTags: List<String> = emptyList(),
    onSelectTag: (String?) -> Unit = {},
    recentSearches: List<SearchHistoryEntity> = emptyList(),
    onReRunSearch: (SearchHistoryEntity) -> Unit = {},
    onDeleteHistoryItem: (Long) -> Unit = {},
    onClearHistory: () -> Unit = {},
    selectedFileType: String? = null,
    availableFileTypes: List<String> = emptyList(),
    fileTypeCounts: Map<String, Int> = emptyMap(),
    onSelectFileType: (String?) -> Unit = {},
    selectedConfidenceTier: String? = null,
    onSelectConfidenceTier: (String?) -> Unit = {},
    sortOrder: com.example.engine.SearchSortOrder = com.example.engine.SearchSortOrder.RELEVANCE,
    onSelectSortOrder: (com.example.engine.SearchSortOrder) -> Unit = {},
    selectedDatePreset: DateRangePreset = DateRangePreset.ALL_TIME,
    startDateMillis: Long? = null,
    endDateMillis: Long? = null,
    onSelectDatePreset: (DateRangePreset) -> Unit = {},
    onSelectDateRange: (Long?, Long?) -> Unit = { _, _ -> },
    onClearDateFilter: () -> Unit = {},
    isMultiSelectMode: Boolean = false,
    selectedDocumentUris: Set<String> = emptySet(),
    onToggleMultiSelectMode: (Boolean?) -> Unit = {},
    onToggleDocumentSelection: (String) -> Unit = {},
    onBatchDeleteRequested: () -> Unit = {},
    onPreviewClick: (SearchResult) -> Unit = {},
    onAddTagClick: (fileUri: String, fileName: String) -> Unit = { _, _ -> },
    onOpenFolder: () -> Unit = {},
    onLoadSampleClick: () -> Unit = {},
    onIndexDownloadsClick: () -> Unit = {},
    onPickFilesClick: () -> Unit = {},
    onResetFilters: () -> Unit = {},
    onOpenFilterSheet: () -> Unit = {},
    onOpenGlossyOverlay: ((SearchResult) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val isSearchActive = query.isNotBlank() || selectedTag != null
    var isVisualGridMode by remember { mutableStateOf(false) }
    val isDateFiltered = selectedDatePreset != DateRangePreset.ALL_TIME || startDateMillis != null || endDateMillis != null
    val hasActiveFilters = selectedFileType != null ||
            selectedConfidenceTier != null ||
            selectedTag != null ||
            sortOrder != com.example.engine.SearchSortOrder.RELEVANCE ||
            isDateFiltered

    val activeFilterCount = (if (selectedFileType != null) 1 else 0) +
            (if (selectedConfidenceTier != null) 1 else 0) +
            (if (isDateFiltered) 1 else 0) +
            (if (sortOrder != com.example.engine.SearchSortOrder.RELEVANCE) 1 else 0) +
            (if (selectedTag != null) 1 else 0)

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Search Bar Text Field
        FtsSearchTextField(
            query = query,
            onQueryChanged = onQueryChanged,
            selectedTag = selectedTag,
            isSearching = isSearching,
            recentSearches = recentSearches,
            focusRequester = focusRequester,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Search Modes & Quick Filter Chips
        if (!isSearchActive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    items(SearchMode.entries) { mode ->
                        val selected = searchMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { onSearchModeChanged(mode) },
                            label = {
                                Text(
                                    text = mode.displayName,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("fts_filter_chip_${mode.name.lowercase()}")
                        )
                    }
                }

                IconButton(
                    onClick = onOpenFolder,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("fts_btn_open_folder")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Manage folders",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Tag Filter Chips (when not actively querying)
            if (allTags.isNotEmpty() || selectedTag != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Label,
                        contentDescription = "Filter by Tag",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedTag == null,
                                onClick = { onSelectTag(null) },
                                label = {
                                    Text(
                                        text = "All Tags",
                                        fontSize = 11.sp,
                                        fontWeight = if (selectedTag == null) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.testTag("fts_tag_chip_all")
                            )
                        }
                        items(allTags) { tag ->
                            val isSelected = selectedTag == tag
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) onSelectTag(null) else onSelectTag(tag)
                                },
                                label = {
                                    Text(
                                        text = "#$tag",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier.testTag("fts_tag_chip_$tag")
                            )
                        }
                    }
                }
            }
        } else {
            // SEARCHING / RESULTS MODE:
            // Active Filter Chips Summary (Removable Badges)
            if (hasActiveFilters) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    // Reset all button
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onResetFilters() }
                                .testTag("fts_btn_quick_reset_filters")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Reset filters",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Reset All",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Active File Type Badge
                    if (selectedFileType != null) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectFileType(null) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Type: .${selectedFileType.lowercase()}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove file type filter",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Active Date Badge
                    if (isDateFiltered) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onClearDateFilter() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Date: ${formatDateRangeLabel(startDateMillis, endDateMillis, selectedDatePreset)}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear date filter",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Active Sort Order Badge
                    if (sortOrder != com.example.engine.SearchSortOrder.RELEVANCE) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectSortOrder(com.example.engine.SearchSortOrder.RELEVANCE) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Sort: ${sortOrder.shortName}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Reset sort to relevance",
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Active Tag Badge
                    if (selectedTag != null) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectTag(null) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Tag: #$selectedTag",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove tag filter",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Active Confidence Tier Badge
                    if (selectedConfidenceTier != null) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectConfidenceTier(null) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Quality: $selectedConfidenceTier",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove quality filter",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Interactive Filter Bar: Filter & Sort Button + Quick File Types + Latency
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (hasActiveFilters) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenFilterSheet() }
                        .testTag("fts_btn_open_filters")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = null,
                            tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (activeFilterCount > 0) "Filters ($activeFilterCount)" else "Filter & Sort",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Toggle List vs Grid View Button
                IconButton(
                    onClick = { isVisualGridMode = !isVisualGridMode },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("btn_toggle_grid_view")
                ) {
                    Icon(
                        imageVector = if (isVisualGridMode) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = if (isVisualGridMode) "Switch to List View" else "Switch to Visual Image Grid",
                        tint = if (isVisualGridMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Dynamic File Format Chips with accurate real counts
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // All chip
                    item {
                        val isAll = selectedFileType == null
                        FilterChip(
                            selected = isAll,
                            onClick = { onSelectFileType(null) },
                            label = {
                                Text(
                                    text = if (rawResults.isNotEmpty()) "All (${rawResults.size})" else "All",
                                    fontSize = 11.sp,
                                    fontWeight = if (isAll) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("fts_ext_chip_all")
                        )
                    }

                    val dynamicExtensions = linkedSetOf<String>().apply {
                        addAll(availableFileTypes)
                        if (size < 3) {
                            addAll(listOf("pdf", "txt", "md", "docx"))
                        }
                    }.toList()

                    items(dynamicExtensions) { ext ->
                        val isSelected = selectedFileType.equals(ext, ignoreCase = true)
                        val count = fileTypeCounts[ext.lowercase()] ?: 0
                        val badgeColor = getFileTypeBadgeColor(ext)

                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) onSelectFileType(null) else onSelectFileType(ext)
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor)
                                )
                            },
                            label = {
                                Text(
                                    text = if (count > 0) ".${ext.lowercase()} ($count)" else ".${ext.lowercase()}",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.testTag("fts_ext_chip_${ext.lowercase()}")
                        )
                    }
                }

                if (latencyMs > 0L) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "FTS Latency",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "${latencyMs}ms",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Document Search Results List Area
        Box(modifier = Modifier.weight(1f)) {
            when {
                // Initial State: Empty query and no tag selected
                query.isBlank() && selectedTag == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (totalChunks == 0) {
                            EmptyIndexStateIllustration(
                                onScanFoldersClick = onOpenFolder,
                                onIndexDownloadsClick = onIndexDownloadsClick,
                                onLoadSamplesClick = onLoadSampleClick,
                                onPickFilesClick = onPickFilesClick
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // Rotating Search & Feature Tips
                        RotatingTipsCard(
                            onTipClick = onQueryChanged,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        if (recentSearches.isNotEmpty()) {
                            SearchHistorySection(
                                historyItems = recentSearches,
                                onSelectQuery = onReRunSearch,
                                onDeleteItem = onDeleteHistoryItem,
                                onClearAll = onClearHistory
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        FtsInitialQueryPrompt(
                            totalChunks = totalChunks,
                            onSuggestionClick = onQueryChanged,
                            onOpenDrawer = onOpenFolder
                        )
                    }
                }

                // Hybrid engine is still embedding the query / scanning vectors / running the FTS query
                results.isEmpty() && isSearching -> {
                    SearchResultsShimmer(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                }

                // Filtered out all results
                rawResults.isNotEmpty() && results.isEmpty() -> {
                    FtsFilteredEmptyPrompt(
                        selectedFileType = selectedFileType,
                        selectedTier = selectedConfidenceTier,
                        selectedDatePreset = selectedDatePreset,
                        startDateMillis = startDateMillis,
                        endDateMillis = endDateMillis,
                        totalCount = rawResults.size,
                        onClearFilter = onResetFilters
                    )
                }

                // No matches found for current search query
                results.isEmpty() -> {
                    SearchEmptyState(
                        query = query.ifBlank { selectedTag ?: "" },
                        searchMode = searchMode,
                        hasActiveFilters = hasActiveFilters,
                        onSwitchToHybrid = { onSearchModeChanged(SearchMode.HYBRID) },
                        onClearFilters = {
                            onQueryChanged("")
                            onSelectTag(null)
                            onResetFilters()
                        },
                        onAddDocuments = onOpenFolder
                    )
                }

                // Active FTS Search Results List / Visual Grid
                else -> {
                    if (isVisualGridMode || selectedFileType.equals("images", ignoreCase = true) || selectedFileType.equals("jpg", ignoreCase = true) || selectedFileType.equals("png", ignoreCase = true)) {
                        VisualImageResultsGrid(
                            results = results,
                            searchQuery = query,
                            onPreviewClick = onPreviewClick,
                            onTagClick = onSelectTag,
                            onAddTagClick = onAddTagClick,
                            isMultiSelectMode = isMultiSelectMode,
                            selectedDocumentUris = selectedDocumentUris,
                            onToggleSelect = onToggleDocumentSelection,
                            onLongClick = { res ->
                                onToggleMultiSelectMode(true)
                                onToggleDocumentSelection(res.fileUri)
                            },
                            onExpandGlossyOverlay = onOpenGlossyOverlay
                        )
                    } else {
                        if (searchMode == SearchMode.KEYWORD && query.isNotBlank()) {
                            SearchResultList(
                                query = query,
                                results = results,
                                isSearching = isSearching,
                                onResultClick = onPreviewClick,
                                searchMode = searchMode,
                                hasActiveFilters = hasActiveFilters,
                                onSwitchToHybrid = { onSearchModeChanged(SearchMode.HYBRID) },
                                onClearFilters = onResetFilters,
                                onAddDocuments = onOpenFolder,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            FtsSearchResultsList(
                                query = query,
                                results = results,
                                totalFiles = totalFiles,
                                isMultiSelectMode = isMultiSelectMode,
                                selectedDocumentUris = selectedDocumentUris,
                                onToggleMultiSelectMode = { onToggleMultiSelectMode(it) },
                                onToggleDocumentSelection = onToggleDocumentSelection,
                                onBatchDeleteRequested = onBatchDeleteRequested,
                                onPreviewClick = onPreviewClick,
                                onSelectTag = onSelectTag,
                                onAddTagClick = onAddTagClick,
                                onOpenGlossyOverlay = onOpenGlossyOverlay
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Search Input Text Field component configured for SQLite FTS queries.
 */
@Composable
fun FtsSearchTextField(
    query: String,
    onQueryChanged: (String) -> Unit,
    selectedTag: String? = null,
    isSearching: Boolean = false,
    recentSearches: List<SearchHistoryEntity> = emptyList(),
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier
            .testTag("fts_search_text_field")
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        placeholder = {
            Text(
                text = if (selectedTag != null) "Search in #$selectedTag with FTS…" else "Search FTS full-text index…",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "FTS Search Icon",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChanged("") },
                    modifier = Modifier.testTag("clear_search_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search query",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (recentSearches.isNotEmpty() && focusRequester != null) {
                IconButton(
                    onClick = {
                        try { focusRequester.requestFocus() } catch (_: Exception) {}
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Recent FTS searches",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        singleLine = true
    )
}

/**
 * Scrollable list area displaying document search results retrieved from the SQLite FTS engine.
 */
@Composable
fun FtsSearchResultsList(
    query: String,
    results: List<SearchResult>,
    totalFiles: Int,
    isMultiSelectMode: Boolean = false,
    selectedDocumentUris: Set<String> = emptySet(),
    onToggleMultiSelectMode: (Boolean?) -> Unit = {},
    onToggleDocumentSelection: (String) -> Unit = {},
    onBatchDeleteRequested: () -> Unit = {},
    onPreviewClick: (SearchResult) -> Unit = {},
    onSelectTag: (String?) -> Unit = {},
    onAddTagClick: (fileUri: String, fileName: String) -> Unit = { _, _ -> },
    onOpenGlossyOverlay: ((SearchResult) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Results Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (query.isNotBlank()) "FTS Search Results (${results.size})" else "Indexed Documents ($totalFiles)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isMultiSelectMode && selectedDocumentUris.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onBatchDeleteRequested() }
                            .testTag("fts_btn_delete_selected_header")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Delete (${selectedDocumentUris.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isMultiSelectMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleMultiSelectMode(null) }
                        .testTag("fts_btn_multi_select")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isMultiSelectMode) Icons.Default.Close else Icons.Default.Checklist,
                            contentDescription = if (isMultiSelectMode) "Exit Multi-Select" else "Multi-Select",
                            tint = if (isMultiSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isMultiSelectMode) "Done" else "Multi-Select",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isMultiSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // LazyColumn List Area
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("fts_search_results_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            items(
                items = results,
                key = { it.chunkId }
            ) { result ->
                SearchResultCard(
                    result = result,
                    searchQuery = query,
                    onPreviewClick = onPreviewClick,
                    onTagClick = { tag -> onSelectTag(tag) },
                    onAddTagClick = onAddTagClick,
                    isMultiSelectMode = isMultiSelectMode,
                    isSelected = selectedDocumentUris.contains(result.fileUri),
                    onToggleSelect = { uri -> onToggleDocumentSelection(uri) },
                    onLongClick = {
                        onToggleMultiSelectMode(true)
                        onToggleDocumentSelection(result.fileUri)
                    },
                    onExpandGlossyOverlay = onOpenGlossyOverlay
                )
            }
        }
    }
}

@Composable
fun FtsInitialQueryPrompt(
    totalChunks: Int,
    onSuggestionClick: (String) -> Unit,
    onOpenDrawer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FindInPage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (totalChunks > 0) "Instant FTS Document Search" else "Index Documents First",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (totalChunks > 0)
                "Type keywords or phrases in the search bar above to query SQLite FTS virtual tables."
            else
                "Open the ☰ Menu to index folders, load sample technical papers, or manage storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        if (totalChunks > 0) {
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Try these sample queries:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val suggestions = listOf(
                "SQLite FTS5 virtual table",
                "Tensor edge TPU quantization",
                "Recurrent neural network",
                "Vector cosine similarity"
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                suggestions.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onSuggestionClick(item) }
                            ) {
                                Text(
                                    text = item,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
fun FtsFilteredEmptyPrompt(
    selectedFileType: String?,
    selectedTier: String?,
    selectedDatePreset: DateRangePreset,
    startDateMillis: Long?,
    endDateMillis: Long?,
    totalCount: Int,
    onClearFilter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Filtered Out All Results",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))

        val activeFiltersDesc = buildString {
            if (selectedFileType != null) append(".${selectedFileType.lowercase()} ")
            if (selectedTier != null) append("$selectedTier confidence ")
            if (selectedDatePreset != DateRangePreset.ALL_TIME || startDateMillis != null) {
                append("(${formatDateRangeLabel(startDateMillis, endDateMillis, selectedDatePreset)})")
            }
        }

        Text(
            text = "Your search matched $totalCount document(s), but none match the active filters: $activeFiltersDesc.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        FilledTonalButton(
            onClick = onClearFilter,
            modifier = Modifier.testTag("btn_clear_filter_empty_state")
        ) {
            Text("Reset All Filters")
        }
    }
}
