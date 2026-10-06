package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.ConfidenceDistribution
import com.example.engine.DateRangePreset
import com.example.engine.SearchMode
import com.example.engine.SearchResult
import com.example.engine.SearchSortOrder
import com.example.engine.formatDateRangeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFilterBottomSheet(
    searchMode: SearchMode,
    onSearchModeChanged: (SearchMode) -> Unit,
    selectedTag: String?,
    allTags: List<String>,
    onSelectTag: (String?) -> Unit,
    selectedFileType: String?,
    availableFileTypes: List<String>,
    fileTypeCounts: Map<String, Int>,
    totalResultsCount: Int,
    onSelectFileType: (String?) -> Unit,
    selectedConfidenceTier: String?,
    onSelectConfidenceTier: (String?) -> Unit,
    confidenceDistribution: ConfidenceDistribution,
    rawResults: List<SearchResult>,
    isDarkTheme: Boolean,
    onResultClick: (SearchResult) -> Unit,
    currentSortOrder: SearchSortOrder,
    onSelectSortOrder: (SearchSortOrder) -> Unit,
    selectedDatePreset: DateRangePreset,
    startDateMillis: Long?,
    endDateMillis: Long?,
    vectorWeight: Float = 0.5f,
    bm25Weight: Float = 0.5f,
    onSetHybridWeights: (Float, Float) -> Unit = { _, _ -> },
    onSelectDatePreset: (DateRangePreset) -> Unit,
    onSelectDateRange: (Long?, Long?) -> Unit,
    onClearDateFilter: () -> Unit,
    onResetFilters: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDateRangePicker by remember { mutableStateOf(false) }

    val isDateFiltered = selectedDatePreset != DateRangePreset.ALL_TIME || startDateMillis != null || endDateMillis != null
    val hasActiveFilters = selectedFileType != null ||
            selectedConfidenceTier != null ||
            selectedTag != null ||
            currentSortOrder != SearchSortOrder.RELEVANCE ||
            isDateFiltered

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Title + Reset Button + Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Search & Filter Options",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$totalResultsCount total candidates available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (hasActiveFilters) {
                    TextButton(
                        onClick = onResetFilters,
                        modifier = Modifier.testTag("btn_reset_all_filters_sheet")
                    ) {
                        Text("Reset All", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Search Mode Engine (Hybrid RRF / Vector KNN / Keyword FTS)
            FilterSectionHeader(icon = Icons.Default.Search, title = "Search Engine Mode")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchMode.entries.forEach { mode ->
                    val isSelected = searchMode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSearchModeChanged(mode) },
                        label = {
                            Text(
                                text = mode.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sheet_chip_${mode.name.lowercase()}")
                    )
                }
            }

            if (searchMode == SearchMode.HYBRID) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Hybrid Ranking Weights (Vector Cosine vs BM25):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                val weightPresets = listOf(
                    Triple("50/50 Balanced", 0.5f, 0.5f),
                    Triple("70% Vector / 30% BM25", 0.7f, 0.3f),
                    Triple("30% Vector / 70% BM25", 0.3f, 0.7f),
                    Triple("90% Vector (Semantic)", 0.9f, 0.1f),
                    Triple("90% BM25 (Exact Keywords)", 0.1f, 0.9f)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(weightPresets) { (label, vW, bW) ->
                        val isSelected = kotlin.math.abs(vectorWeight - vW) < 0.05f && kotlin.math.abs(bm25Weight - bW) < 0.05f
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSetHybridWeights(vW, bW) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 2. Category / Tags Filter
            if (allTags.isNotEmpty() || selectedTag != null) {
                FilterSectionHeader(icon = Icons.AutoMirrored.Filled.Label, title = "Filter by Tag")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = selectedTag == null,
                            onClick = { onSelectTag(null) },
                            label = { Text("All Tags", fontSize = 12.sp) },
                            modifier = Modifier.testTag("sheet_tag_all")
                        )
                    }
                    items(allTags) { tag ->
                        val isSelected = selectedTag == tag
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectTag(if (isSelected) null else tag) },
                            label = { Text("#$tag", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.testTag("sheet_tag_$tag")
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // 3. File Format Filter
            FilterSectionHeader(icon = Icons.Default.FilterList, title = "File Format")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    val isAll = selectedFileType == null
                    FilterChip(
                        selected = isAll,
                        onClick = { onSelectFileType(null) },
                        label = {
                            Text(
                                text = if (totalResultsCount > 0) "All ($totalResultsCount)" else "All Types",
                                fontSize = 12.sp,
                                fontWeight = if (isAll) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("sheet_type_all")
                    )
                }

                val displayExtensions = linkedSetOf<String>().apply {
                    addAll(availableFileTypes)
                    if (size < 4) {
                        addAll(listOf("pdf", "txt", "md", "docx"))
                    }
                }.toList()

                items(displayExtensions) { ext ->
                    val isSelected = selectedFileType.equals(ext, ignoreCase = true)
                    val count = fileTypeCounts[ext.lowercase()] ?: 0
                    val badgeColor = getFileTypeBadgeColor(ext)

                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectFileType(if (isSelected) null else ext) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                        },
                        label = {
                            Text(
                                text = if (count > 0) ".${ext.lowercase()} ($count)" else ".${ext.lowercase()}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.testTag("sheet_type_${ext.lowercase()}")
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 4. Sort Order
            FilterSectionHeader(icon = Icons.AutoMirrored.Filled.Sort, title = "Sort Results")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(SearchSortOrder.entries.toTypedArray()) { order ->
                    val isSelected = currentSortOrder == order
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectSortOrder(order) },
                        label = {
                            Text(
                                text = order.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        modifier = Modifier.testTag("sheet_sort_${order.name.lowercase()}")
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 5. Date Range Filter
            FilterSectionHeader(icon = Icons.Default.CalendarToday, title = "Date Range")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DateRangePreset.entries.forEach { preset ->
                    if (preset == DateRangePreset.CUSTOM) {
                        item {
                            val isCustom = selectedDatePreset == DateRangePreset.CUSTOM
                            FilterChip(
                                selected = isCustom,
                                onClick = { showDateRangePicker = true },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.CalendarMonth,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = if (startDateMillis != null || endDateMillis != null) {
                                            formatDateRangeLabel(startDateMillis, endDateMillis, DateRangePreset.CUSTOM)
                                        } else "Custom Range…",
                                        fontSize = 12.sp
                                    )
                                },
                                modifier = Modifier.testTag("sheet_date_custom")
                            )
                        }
                    } else {
                        item {
                            val isSelected = selectedDatePreset == preset
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectDatePreset(preset) },
                                label = { Text(preset.displayName, fontSize = 12.sp) },
                                modifier = Modifier.testTag("sheet_date_${preset.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 6. Match Confidence Quality & Histogram Card
            FilterSectionHeader(icon = Icons.Default.Speed, title = "Match Confidence & Quality")
            RechartsConfidenceDistributionCard(
                distribution = confidenceDistribution,
                topResults = rawResults,
                isDarkTheme = isDarkTheme,
                selectedTier = selectedConfidenceTier,
                onSelectTier = onSelectConfidenceTier,
                onResultClick = onResultClick
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 7. Bottom Done Button
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_sheet_done"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Apply Filters & View Results",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Material 3 Date Range Picker Dialog
    if (showDateRangePicker) {
        val dateRangePickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = startDateMillis,
            initialSelectedEndDateMillis = endDateMillis
        )

        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = dateRangePickerState.selectedStartDateMillis
                        val endRaw = dateRangePickerState.selectedEndDateMillis ?: start
                        val end = if (endRaw != null) endRaw + 86_399_999L else null
                        onSelectDateRange(start, end)
                        showDateRangePicker = false
                    },
                    enabled = dateRangePickerState.selectedStartDateMillis != null,
                    modifier = Modifier.testTag("btn_sheet_apply_date_range")
                ) {
                    Text("Apply Range", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    if (isDateFiltered) {
                        TextButton(
                            onClick = {
                                onClearDateFilter()
                                showDateRangePicker = false
                            }
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { showDateRangePicker = false }) {
                        Text("Cancel")
                    }
                }
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                title = {
                    Text(
                        text = "Filter by Document Date",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                    )
                },
                headline = {
                    Text(
                        text = "Select start and end dates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun FilterSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
