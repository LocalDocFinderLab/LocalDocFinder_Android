package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import com.example.engine.DateRangePreset
import com.example.engine.SearchSortOrder
import com.example.engine.formatDateRangeLabel

/**
 * Filter & Sort Control Bar for Search Results.
 * Allows users to:
 * 1. Filter results by file format (e.g., .txt, .pdf, .md, .docx).
 * 2. Filter results by creation/last-modified date range with quick presets and interactive calendar picker.
 * 3. Sort dynamically by Relevance (RRF/Cosine), Date Indexed, or File Name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFilterSortBar(
    selectedFileType: String?, // null = All
    availableFileTypes: List<String>,
    fileTypeCounts: Map<String, Int>,
    totalResultsCount: Int,
    currentSortOrder: SearchSortOrder,
    selectedDatePreset: DateRangePreset = DateRangePreset.ALL_TIME,
    startDateMillis: Long? = null,
    endDateMillis: Long? = null,
    onSelectFileType: (String?) -> Unit,
    onSelectSortOrder: (SearchSortOrder) -> Unit,
    onSelectDatePreset: (DateRangePreset) -> Unit = {},
    onSelectDateRange: (start: Long?, end: Long?) -> Unit = { _, _ -> },
    onClearDateFilter: () -> Unit = {},
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDateRangePicker by remember { mutableStateOf(false) }

    val isDateFiltered = selectedDatePreset != DateRangePreset.ALL_TIME || startDateMillis != null || endDateMillis != null
    val isFilteredOrSorted = selectedFileType != null ||
            currentSortOrder != SearchSortOrder.RELEVANCE ||
            isDateFiltered

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. File Type Filter Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Filter by file format",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Type:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .testTag("filter_bar_file_types")
            ) {
                // "All Types" chip
                item {
                    val isAll = selectedFileType == null
                    FilterChip(
                        selected = isAll,
                        onClick = { onSelectFileType(null) },
                        label = {
                            Text(
                                text = if (totalResultsCount > 0) "All ($totalResultsCount)" else "All Types",
                                fontSize = 11.sp,
                                fontWeight = if (isAll) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("filter_chip_all_types")
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
                    val extLabel = ".${ext.lowercase()}"
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
                                text = if (count > 0) "$extLabel ($count)" else extLabel,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.testTag("filter_chip_${ext.lowercase()}")
                    )
                }
            }

            // Quick reset button when active filters exist
            AnimatedVisibility(
                visible = isFilteredOrSorted,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IconButton(
                    onClick = onResetFilters,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("btn_reset_filters")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Reset filters and sort",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 2. Date Range Filter Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = "Filter by document date",
                    tint = if (isDateFiltered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Date:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .testTag("filter_bar_date_range")
            ) {
                // If a custom or active range is set, show active badge first
                if (isDateFiltered) {
                    item {
                        val activeLabel = formatDateRangeLabel(startDateMillis, endDateMillis, selectedDatePreset)
                        FilterChip(
                            selected = true,
                            onClick = { showDateRangePicker = true },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = onClearDateFilter,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .testTag("btn_clear_date_chip")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear date filter",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = activeLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("filter_chip_date_active")
                        )
                    }
                }

                // "All Dates" chip
                item {
                    val isAllDates = selectedDatePreset == DateRangePreset.ALL_TIME && startDateMillis == null && endDateMillis == null
                    FilterChip(
                        selected = isAllDates,
                        onClick = { onSelectDatePreset(DateRangePreset.ALL_TIME) },
                        label = {
                            Text(
                                text = "All Dates",
                                fontSize = 11.sp,
                                fontWeight = if (isAllDates) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.testTag("filter_chip_date_all")
                    )
                }

                // Preset: Today
                item {
                    val isSelected = selectedDatePreset == DateRangePreset.TODAY
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDatePreset(DateRangePreset.TODAY) },
                        label = {
                            Text(
                                text = "Today",
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("filter_chip_date_today")
                    )
                }

                // Preset: Past 7 Days
                item {
                    val isSelected = selectedDatePreset == DateRangePreset.PAST_7_DAYS
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDatePreset(DateRangePreset.PAST_7_DAYS) },
                        label = {
                            Text(
                                text = "Past 7d",
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("filter_chip_date_7d")
                    )
                }

                // Preset: Past 30 Days
                item {
                    val isSelected = selectedDatePreset == DateRangePreset.PAST_30_DAYS
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDatePreset(DateRangePreset.PAST_30_DAYS) },
                        label = {
                            Text(
                                text = "Past 30d",
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("filter_chip_date_30d")
                    )
                }

                // Preset: Past 90 Days
                item {
                    val isSelected = selectedDatePreset == DateRangePreset.PAST_90_DAYS
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDatePreset(DateRangePreset.PAST_90_DAYS) },
                        label = {
                            Text(
                                text = "Past 90d",
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.testTag("filter_chip_date_90d")
                    )
                }

                // Custom Range Picker Chip
                item {
                    val isCustom = selectedDatePreset == DateRangePreset.CUSTOM
                    FilterChip(
                        selected = isCustom,
                        onClick = { showDateRangePicker = true },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "Custom Range…",
                                fontSize = 11.sp,
                                fontWeight = if (isCustom) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        modifier = Modifier.testTag("filter_chip_date_custom")
                    )
                }
            }
        }

        // 3. Sort Options Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort search results",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Sort:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .testTag("sort_bar_options")
            ) {
                items(SearchSortOrder.entries.toTypedArray()) { order ->
                    val isSelected = currentSortOrder == order
                    val tagSuffix = when (order) {
                        SearchSortOrder.RELEVANCE -> "relevance"
                        SearchSortOrder.DATE_DESC -> "date_desc"
                        SearchSortOrder.DATE_ASC -> "date_asc"
                        SearchSortOrder.FILE_SIZE -> "file_size"
                        SearchSortOrder.NAME_ASC -> "name_asc"
                        SearchSortOrder.NAME_DESC -> "name_desc"
                    }

                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectSortOrder(order) },
                        label = {
                            Text(
                                text = order.shortName,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ),
                        modifier = Modifier.testTag("sort_chip_$tagSuffix")
                    )
                }
            }
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
                    modifier = Modifier.testTag("btn_apply_date_range")
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
                            },
                            modifier = Modifier.testTag("btn_dialog_clear_date")
                        ) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = { showDateRangePicker = false }
                    ) {
                        Text("Cancel")
                    }
                }
            },
            modifier = Modifier.testTag("date_range_picker_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                // Quick preset shortcuts header inside the dialog
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Quick Presets:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp)
                    )
                    listOf(
                        DateRangePreset.TODAY,
                        DateRangePreset.PAST_7_DAYS,
                        DateRangePreset.PAST_30_DAYS
                    ).forEach { preset ->
                        FilterChip(
                            selected = selectedDatePreset == preset,
                            onClick = {
                                onSelectDatePreset(preset)
                                showDateRangePicker = false
                            },
                            label = { Text(preset.shortName, fontSize = 10.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                DateRangePicker(
                    state = dateRangePickerState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp),
                    title = {
                        Text(
                            text = "Filter by Document Timestamp",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                        )
                    },
                    headline = {
                        Text(
                            text = "Select creation or last-modified range",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )
                    }
                )
            }
        }
    }
}

/**
 * Returns distinct color coding for file type chips and badges.
 */
fun getFileTypeBadgeColor(ext: String): Color {
    return when (ext.lowercase().removePrefix(".")) {
        "pdf" -> Color(0xFFE11D48) // Crimson red
        "txt", "text" -> Color(0xFF0D9488) // Teal
        "md", "markdown" -> Color(0xFF8B5CF6) // Purple
        "docx", "doc" -> Color(0xFF2563EB) // Blue
        "json", "xml", "csv" -> Color(0xFFD97706) // Amber
        "jpg", "jpeg", "png", "webp" -> Color(0xFF10B981) // Emerald green
        else -> Color(0xFF64748B) // Slate gray
    }
}
