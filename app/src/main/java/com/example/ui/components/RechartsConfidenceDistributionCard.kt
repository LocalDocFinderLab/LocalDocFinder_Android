package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.ConfidenceDistribution
import com.example.engine.ScoreBin
import com.example.engine.SearchResult
import com.example.engine.TopKConfidenceScore
import com.example.engine.extractTopKConfidenceScores
import org.json.JSONArray
import org.json.JSONObject

enum class ChartDisplayMode(val label: String) {
    TOP_K("Top-K Confidence"),
    DISTRIBUTION("Score Bins")
}

/**
 * Visualizes confidence scores of the top-k search results returned by the vector engine
 * with charts drawn natively on a Compose canvas. (An earlier version rendered them with Recharts in a
 * WebView that loaded its scripts from a CDN; nothing in this app may touch the network except model
 * downloads and update checks, so that path is gone.)
 */
@Composable
fun RechartsConfidenceDistributionCard(
    distribution: ConfidenceDistribution,
    topResults: List<SearchResult> = emptyList(),
    isDarkTheme: Boolean,
    selectedTier: String?, // null = All, "HIGH" (>=70%), "MODERATE" (40-69%), "LOW" (<40%)
    onSelectTier: (String?) -> Unit,
    onResultClick: ((SearchResult) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    var selectedK by remember { mutableIntStateOf(10) }
    var chartMode by remember { mutableStateOf(if (topResults.isNotEmpty()) ChartDisplayMode.TOP_K else ChartDisplayMode.DISTRIBUTION) }

    if (distribution.totalCount == 0 && topResults.isEmpty()) return

    val topKScores = remember(topResults, selectedK) {
        extractTopKConfidenceScores(topResults, k = selectedK)
    }

    // Metrics for Top-K
    val topKMaxScore = remember(topKScores) { topKScores.maxOfOrNull { it.scorePercentage } ?: 0f }
    val topKAvgScore = remember(topKScores) {
        if (topKScores.isNotEmpty()) topKScores.map { it.scorePercentage }.average().toFloat() else 0f
    }
    val highConfidenceTopKCount = remember(topKScores) { topKScores.count { it.tier == "HIGH" } }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("confidence_distribution_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Title, Quality Pill, and Mode / View Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (chartMode == ChartDisplayMode.TOP_K) Icons.Default.Leaderboard else Icons.Default.AutoGraph,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (chartMode == ChartDisplayMode.TOP_K) "Top-K Vector Confidence" else "Match Quality Distribution",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = if (chartMode == ChartDisplayMode.TOP_K) {
                                "Cosine confidence of top-${topKScores.size} results • Top: %.1f%%".format(topKMaxScore)
                            } else {
                                distribution.qualityAssessment
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Switch chart mode: Top-K Confidence vs Distribution Histogram
                    IconButton(
                        onClick = {
                            chartMode = if (chartMode == ChartDisplayMode.TOP_K) {
                                ChartDisplayMode.DISTRIBUTION
                            } else {
                                ChartDisplayMode.TOP_K
                            }
                        },
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("btn_toggle_chart_mode")
                    ) {
                        Icon(
                            imageVector = if (chartMode == ChartDisplayMode.TOP_K) Icons.Default.QueryStats else Icons.Default.Leaderboard,
                            contentDescription = if (chartMode == ChartDisplayMode.TOP_K) "Switch to Distribution Histogram" else "Switch to Top-K Confidence Bar Chart",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // Expand / Collapse toggle
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("btn_toggle_chart_expand")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse Chart" else "Expand Chart",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Summary Stats Pill Bar
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chartMode == ChartDisplayMode.TOP_K) {
                    MetricPill(
                        label = "Top Match",
                        value = "%.1f%%".format(topKMaxScore),
                        color = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(
                        label = "Top-$selectedK Avg",
                        value = "%.1f%%".format(topKAvgScore),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(
                        label = "High Match",
                        value = "$highConfidenceTopKCount/${topKScores.size}",
                        color = Color(0xFF6366F1),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    MetricPill(
                        label = "Average",
                        value = "%.0f%%".format(distribution.averageScore * 100),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(
                        label = "Peak Match",
                        value = "%.0f%%".format(distribution.maxScore * 100),
                        color = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(
                        label = "High Quality",
                        value = "${distribution.highConfidenceCount}/${distribution.totalCount}",
                        color = Color(0xFF6366F1),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // Controls for Top-K size if in TOP_K mode
                    if (chartMode == ChartDisplayMode.TOP_K && topResults.size > 5) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Top-K Candidates:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(5, 10, 15).forEach { k ->
                                    val isSelected = selectedK == k
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { selectedK = k }
                                            .testTag("top_k_chip_$k")
                                    ) {
                                        Text(
                                            text = "Top $k",
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Chart, drawn natively (no WebView, no network)
                    if (chartMode == ChartDisplayMode.TOP_K) {
                        NativeTopKConfidenceCanvas(
                            scores = topKScores,
                            onResultClick = { index ->
                                if (index in topResults.indices) {
                                    onResultClick?.invoke(topResults[index])
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(170.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .testTag("native_topk_canvas")
                        )
                    } else {
                        NativeConfidenceCanvas(
                            bins = distribution.bins,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .testTag("native_confidence_canvas")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Interactive Confidence Tier Filter Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedTier == null,
                            onClick = { onSelectTier(null) },
                            label = {
                                Text(
                                    text = "All Scores (${distribution.totalCount})",
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTier == null) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier.testTag("tier_chip_all")
                        )

                        FilterChip(
                            selected = selectedTier == "HIGH",
                            onClick = { onSelectTier(if (selectedTier == "HIGH") null else "HIGH") },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "High ≥70% (${distribution.highConfidenceCount})",
                                        fontSize = 11.sp,
                                        fontWeight = if (selectedTier == "HIGH") FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFF10B981)
                            ),
                            modifier = Modifier.testTag("tier_chip_high")
                        )

                        FilterChip(
                            selected = selectedTier == "MODERATE",
                            onClick = { onSelectTier(if (selectedTier == "MODERATE") null else "MODERATE") },
                            label = {
                                Text(
                                    text = "40-69% (${distribution.moderateConfidenceCount})",
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTier == "MODERATE") FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFFF59E0B)
                            ),
                            modifier = Modifier.testTag("tier_chip_moderate")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * Native Jetpack Compose Canvas for Top-K Vector Confidence Scores.
 */
@Composable
private fun NativeTopKConfidenceCanvas(
    scores: List<TopKConfidenceScore>,
    onResultClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    if (scores.isEmpty()) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No vector results to visualize",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(scores) {
                    detectTapGestures { offset ->
                        val barCount = scores.size
                        if (barCount > 0) {
                            val slotWidth = size.width / barCount
                            val index = (offset.x / slotWidth).toInt().coerceIn(0, barCount - 1)
                            onResultClick(index)
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val barCount = scores.size
            if (barCount == 0) return@Canvas

            val slotWidth = canvasWidth / barCount
            val barWidth = (slotWidth * 0.65f).coerceAtMost(36.dp.toPx())
            val spacing = slotWidth - barWidth

            // Grid lines (0%, 25%, 50%, 75%, 100%)
            val gridLines = 4
            for (i in 0..gridLines) {
                val y = canvasHeight * (i.toFloat() / gridLines)
                drawLine(
                    color = outlineVariant.copy(alpha = 0.35f),
                    start = Offset(0f, y),
                    end = Offset(canvasWidth, y),
                    strokeWidth = 1f
                )
            }

            scores.forEachIndexed { index, item ->
                val fraction = (item.scorePercentage / 100f).coerceIn(0f, 1f)
                val barHeight = fraction * (canvasHeight - 12f)
                val left = index * slotWidth + (spacing / 2f)
                val top = canvasHeight - barHeight

                val color = when (item.tier) {
                    "HIGH" -> Color(0xFF10B981)
                    "MODERATE" -> Color(0xFFF59E0B)
                    else -> Color(0xFFEF4444)
                }

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(color, color.copy(alpha = 0.70f)),
                        startY = top,
                        endY = canvasHeight
                    ),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight.coerceAtLeast(4f)),
                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // X-Axis labels (Rank & Score)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            scores.forEachIndexed { idx, item ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onResultClick(idx) }
                ) {
                    Text(
                        text = "%.0f%%".format(item.scorePercentage),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "#${item.rank}",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Native Jetpack Compose Canvas fallback for confidence distribution histogram visualization.
 */
@Composable
private fun NativeConfidenceCanvas(
    bins: List<ScoreBin>,
    modifier: Modifier = Modifier
) {
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val maxCount = remember(bins) { (bins.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1) }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val barCount = bins.size
            if (barCount == 0) return@Canvas

            val slotWidth = canvasWidth / barCount
            val barWidth = slotWidth * 0.55f
            val spacing = slotWidth * 0.45f

            // Draw horizontal grid lines
            val gridLines = 3
            for (i in 0..gridLines) {
                val y = canvasHeight * (i.toFloat() / gridLines)
                drawLine(
                    color = outlineVariant.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(canvasWidth, y),
                    strokeWidth = 1f
                )
            }

            // Draw bars
            bins.forEachIndexed { index, bin ->
                val barHeight = if (maxCount > 0) {
                    (bin.count.toFloat() / maxCount.toFloat()) * (canvasHeight - 20f)
                } else 0f

                val left = index * slotWidth + (spacing / 2f)
                val top = canvasHeight - barHeight

                val color = when (index) {
                    4 -> Color(0xFF10B981) // 80-100%
                    3 -> Color(0xFF3B82F6) // 60-80%
                    2 -> Color(0xFF6366F1) // 40-60%
                    1 -> Color(0xFFF59E0B) // 20-40%
                    else -> Color(0xFFEF4444) // 0-20%
                }

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(color, color.copy(alpha = 0.65f)),
                        startY = top,
                        endY = canvasHeight
                    ),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight.coerceAtLeast(4f)),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // X-Axis labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            bins.forEach { bin ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "${bin.count}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = bin.label,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
