package com.example.ui.components

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * using Recharts (embedded via responsive WebView with instant offline fallback)
 * and native Compose canvas option.
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
    var useRechartsWebView by remember { mutableStateOf(true) }
    var selectedK by remember { mutableIntStateOf(10) }
    var chartMode by remember { mutableStateOf(if (topResults.isNotEmpty()) ChartDisplayMode.TOP_K else ChartDisplayMode.DISTRIBUTION) }

    if (distribution.totalCount == 0 && topResults.isEmpty()) return

    val topKScores = remember(topResults, selectedK) {
        extractTopKConfidenceScores(topResults, k = selectedK)
    }

    val primaryColorHex = if (isDarkTheme) "#818CF8" else "#4F46E5"
    val textColorHex = if (isDarkTheme) "#E2E8F0" else "#1E293B"
    val mutedColorHex = if (isDarkTheme) "#94A3B8" else "#64748B"
    val gridColorHex = if (isDarkTheme) "rgba(255,255,255,0.08)" else "rgba(0,0,0,0.06)"
    val bgCardHex = if (isDarkTheme) "#18181B" else "#FFFFFF"

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
                            Spacer(modifier = Modifier.width(6.dp))
                            // Recharts Badge
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                            ) {
                                Text(
                                    text = if (useRechartsWebView) "Recharts" else "Native",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
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

                    // Switch chart view button (Recharts Web vs Compose Native)
                    IconButton(
                        onClick = { useRechartsWebView = !useRechartsWebView },
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("btn_toggle_chart_view")
                    ) {
                        Icon(
                            imageVector = if (useRechartsWebView) Icons.Default.BarChart else Icons.Default.Web,
                            contentDescription = if (useRechartsWebView) "Switch to Native Canvas" else "Switch to Recharts Web",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

                    // Chart Container: Either Recharts WebView or Native Canvas
                    if (useRechartsWebView) {
                        RechartsWebViewContainer(
                            chartMode = chartMode,
                            topKScores = topKScores,
                            bins = distribution.bins,
                            isDarkTheme = isDarkTheme,
                            primaryColor = primaryColorHex,
                            textColor = textColorHex,
                            mutedColor = mutedColorHex,
                            gridColor = gridColorHex,
                            bgCard = bgCardHex,
                            onResultClick = { index ->
                                if (index in topResults.indices) {
                                    onResultClick?.invoke(topResults[index])
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .testTag("recharts_webview")
                        )
                    } else {
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
 * Embedded WebView running Recharts React library with instant SVG rendering fallback.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RechartsWebViewContainer(
    chartMode: ChartDisplayMode,
    topKScores: List<TopKConfidenceScore>,
    bins: List<ScoreBin>,
    isDarkTheme: Boolean,
    primaryColor: String,
    textColor: String,
    mutedColor: String,
    gridColor: String,
    bgCard: String,
    onResultClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val topKJson = remember(topKScores) {
        val array = JSONArray()
        topKScores.forEach { item ->
            val obj = JSONObject()
            obj.put("rank", "#${item.rank}")
            obj.put("name", item.fileName)
            obj.put("score", "%.1f".format(item.scorePercentage).toDoubleOrNull() ?: 0.0)
            obj.put("tier", item.tier)
            obj.put("color", item.tierColorHex)
            obj.put("chunk", "Chunk #${item.chunkIndex + 1}")
            obj.put("date", item.formattedDate)
            array.put(obj)
        }
        array.toString()
    }

    val binsJson = remember(bins) {
        val array = JSONArray()
        bins.forEach { bin ->
            val obj = JSONObject()
            obj.put("bin", bin.label)
            obj.put("count", bin.count)
            obj.put("percentage", "%.1f".format(bin.percentage))
            array.put(obj)
        }
        array.toString()
    }

    val modeStr = chartMode.name
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(chartMode, topKJson, binsJson, isDarkTheme) {
        webViewRef?.evaluateJavascript(
            "if (window.updateRechartsData) { window.updateRechartsData('$modeStr', $topKJson, $binsJson, $isDarkTheme, '$primaryColor', '$textColor', '$mutedColor', '$gridColor'); }",
            null
        )
    }

    val htmlDocument = remember(isDarkTheme) {
        generateRechartsHtml(
            initialMode = modeStr,
            initialTopKJson = topKJson,
            initialBinsJson = binsJson,
            isDark = isDarkTheme,
            primaryColor = primaryColor,
            textColor = textColor,
            mutedColor = mutedColor,
            gridColor = gridColor,
            bgCard = bgCard
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onBarClicked(index: Int) {
                        post { onResultClick(index) }
                    }
                }, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(
                            "if (window.updateRechartsData) { window.updateRechartsData('$modeStr', $topKJson, $binsJson, $isDarkTheme, '$primaryColor', '$textColor', '$mutedColor', '$gridColor'); }",
                            null
                        )
                    }
                }
                loadDataWithBaseURL("https://docuvector.ai", htmlDocument, "text/html", "UTF-8", null)
                webViewRef = this
            }
        },
        update = { webView ->
            webView.evaluateJavascript(
                "if (window.updateRechartsData) { window.updateRechartsData('$modeStr', $topKJson, $binsJson, $isDarkTheme, '$primaryColor', '$textColor', '$mutedColor', '$gridColor'); }",
                null
            )
        }
    )
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

/**
 * Builds responsive Recharts HTML container supporting both Top-K confidence score bar chart
 * and score distribution histogram, with interactive tooltips and SVG offline fallback.
 */
private fun generateRechartsHtml(
    initialMode: String,
    initialTopKJson: String,
    initialBinsJson: String,
    isDark: Boolean,
    primaryColor: String,
    textColor: String,
    mutedColor: String,
    gridColor: String,
    bgCard: String
): String {
    return """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    html, body {
      width: 100%;
      height: 100%;
      background: transparent;
      overflow: hidden;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      user-select: none;
      -webkit-user-select: none;
    }
    #app {
      width: 100%;
      height: 100%;
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
    }
    .tooltip {
      background: ${if (isDark) "#27272A" else "#FFFFFF"};
      color: $textColor;
      border: 1px solid ${if (isDark) "#3F3F46" else "#E2E8F0"};
      border-radius: 8px;
      padding: 8px 12px;
      font-size: 11px;
      box-shadow: 0 6px 12px -2px rgba(0,0,0,0.2);
      pointer-events: none;
      max-width: 220px;
    }
    .tooltip-title {
      font-weight: bold;
      margin-bottom: 3px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .tooltip-score {
      font-weight: 700;
      font-size: 12px;
      margin-bottom: 2px;
    }
    .tooltip-meta {
      color: $mutedColor;
      font-size: 10px;
    }
    .svg-bar {
      transition: height 0.3s ease, y 0.3s ease;
      cursor: pointer;
    }
    .svg-bar:hover {
      opacity: 0.85;
    }
  </style>
  <!-- Load Recharts & React from CDN -->
  <script src="https://unpkg.com/react@18/umd/react.production.min.js"></script>
  <script src="https://unpkg.com/react-dom@18/umd/react-dom.production.min.js"></script>
  <script src="https://unpkg.com/recharts@2.12.7/umd/Recharts.min.js"></script>
</head>
<body>
  <div id="app"></div>

  <script>
    let currentMode = '$initialMode'; // "TOP_K" or "DISTRIBUTION"
    let currentTopK = $initialTopKJson;
    let currentBins = $initialBinsJson;
    let isDarkTheme = $isDark;
    let pColor = '$primaryColor';
    let tColor = '$textColor';
    let mColor = '$mutedColor';
    let gColor = '$gridColor';

    function notifyBarClicked(index) {
      if (window.AndroidBridge && window.AndroidBridge.onBarClicked) {
        window.AndroidBridge.onBarClicked(index);
      }
    }

    function renderRechartsComponent() {
      const container = document.getElementById('app');
      if (!container) return;

      // Full Recharts React render if CDN loaded
      if (window.Recharts && window.React && window.ReactDOM) {
        try {
          const { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, CartesianGrid, Cell, ReferenceLine } = window.Recharts;
          const e = React.createElement;

          if (currentMode === 'TOP_K') {
            function TopKTooltip({ active, payload }) {
              if (active && payload && payload.length) {
                const data = payload[0].payload;
                return e('div', { className: 'tooltip' },
                  e('div', { className: 'tooltip-title' }, data.rank + ' ' + data.name),
                  e('div', { className: 'tooltip-score', style: { color: data.color || pColor } }, data.score + '% Vector Match'),
                  e('div', { className: 'tooltip-meta' }, data.chunk + ' • ' + data.date)
                );
              }
              return null;
            }

            function TopKChartApp() {
              return e(ResponsiveContainer, { width: '100%', height: '100%' },
                e(BarChart, {
                  data: currentTopK,
                  margin: { top: 14, right: 10, left: -22, bottom: 2 }
                },
                  e(CartesianGrid, { strokeDasharray: '3 3', vertical: false, stroke: gColor }),
                  e(XAxis, {
                    dataKey: 'rank',
                    tick: { fontSize: 10, fill: mColor },
                    axisLine: false,
                    tickLine: false
                  }),
                  e(YAxis, {
                    domain: [0, 100],
                    unit: '%',
                    allowDecimals: false,
                    tick: { fontSize: 9, fill: mColor },
                    axisLine: false,
                    tickLine: false
                  }),
                  e(ReferenceLine, { y: 70, stroke: '#10B981', strokeDasharray: '2 2', strokeOpacity: 0.6 }),
                  e(Tooltip, { content: e(TopKTooltip) }),
                  e(Bar, {
                    dataKey: 'score',
                    radius: [5, 5, 0, 0],
                    onClick: (entry, index) => notifyBarClicked(index)
                  },
                    currentTopK.map((entry, index) =>
                      e(Cell, { key: 'topk-cell-' + index, fill: entry.color || pColor, cursor: 'pointer' })
                    )
                  )
                )
              );
            }

            ReactDOM.render(e(TopKChartApp), container);
            return;
          } else {
            // Distribution mode
            const barColors = ['#EF4444', '#F59E0B', '#6366F1', '#3B82F6', '#10B981'];

            function DistributionTooltip({ active, payload }) {
              if (active && payload && payload.length) {
                const data = payload[0].payload;
                return e('div', { className: 'tooltip' },
                  e('div', { className: 'tooltip-title' }, 'Confidence: ' + data.bin),
                  e('div', { className: 'tooltip-score', style: { color: pColor } }, data.count + ' match chunks (' + data.percentage + '%)')
                );
              }
              return null;
            }

            function DistChartApp() {
              return e(ResponsiveContainer, { width: '100%', height: '100%' },
                e(BarChart, {
                  data: currentBins,
                  margin: { top: 12, right: 10, left: -22, bottom: 0 }
                },
                  e(CartesianGrid, { strokeDasharray: '3 3', vertical: false, stroke: gColor }),
                  e(XAxis, {
                    dataKey: 'bin',
                    tick: { fontSize: 10, fill: mColor },
                    axisLine: false,
                    tickLine: false
                  }),
                  e(YAxis, {
                    allowDecimals: false,
                    tick: { fontSize: 10, fill: mColor },
                    axisLine: false,
                    tickLine: false
                  }),
                  e(Tooltip, { content: e(DistributionTooltip) }),
                  e(Bar, {
                    dataKey: 'count',
                    radius: [5, 5, 0, 0]
                  },
                    currentBins.map((entry, index) =>
                      e(Cell, { key: 'cell-' + index, fill: barColors[index % barColors.length] })
                    )
                  )
                )
              );
            }

            ReactDOM.render(e(DistChartApp), container);
            return;
          }
        } catch (err) {
          console.warn('Recharts React render error, falling back to SVG renderer:', err);
        }
      }

      // Offline / Fast SVG fallback
      renderSvgFallback(container);
    }

    function renderSvgFallback(container) {
      const width = container.clientWidth || 340;
      const height = container.clientHeight || 170;
      const paddingBottom = 26;
      const paddingTop = 14;
      const chartHeight = height - paddingBottom - paddingTop;

      let barsSvg = '';
      let labelsSvg = '';

      if (currentMode === 'TOP_K') {
        const data = currentTopK;
        if (!data || data.length === 0) return;
        const slotWidth = width / data.length;
        const barWidth = Math.min(slotWidth * 0.65, 36);

        data.forEach((d, i) => {
          const h = (d.score / 100) * (chartHeight - 14);
          const x = i * slotWidth + (slotWidth - barWidth) / 2;
          const y = height - paddingBottom - h;
          const color = d.color || '#10B981';

          barsSvg += `
            <g class="svg-bar" onclick="notifyBarClicked(${'$'}{i})">
              <rect x="${'$'}{x}" y="${'$'}{y}" width="${'$'}{barWidth}" height="${'$'}{Math.max(h, 4)}" rx="4" fill="${'$'}{color}" />
              <text x="${'$'}{x + barWidth / 2}" y="${'$'}{y - 4}" font-size="10" font-weight="bold" fill="${'$'}{tColor}" text-anchor="middle" font-family="monospace">${'$'}{Math.round(d.score)}%</text>
            </g>
          `;

          labelsSvg += `
            <text x="${'$'}{i * slotWidth + slotWidth / 2}" y="${'$'}{height - 8}" font-size="10" font-weight="600" fill="${'$'}{pColor}" text-anchor="middle" onclick="notifyBarClicked(${'$'}{i})">${'$'}{d.rank}</text>
          `;
        });
      } else {
        const data = currentBins;
        const barColors = ['#EF4444', '#F59E0B', '#6366F1', '#3B82F6', '#10B981'];
        const maxCount = Math.max(...data.map(d => d.count), 1);
        const slotWidth = width / data.length;
        const barWidth = slotWidth * 0.55;

        data.forEach((d, i) => {
          const h = (d.count / maxCount) * (chartHeight - 16);
          const x = i * slotWidth + (slotWidth - barWidth) / 2;
          const y = height - paddingBottom - h;
          const color = barColors[i % barColors.length];

          barsSvg += `
            <g class="svg-bar">
              <rect x="${'$'}{x}" y="${'$'}{y}" width="${'$'}{barWidth}" height="${'$'}{Math.max(h, 4)}" rx="5" fill="${'$'}{color}" />
              <text x="${'$'}{x + barWidth / 2}" y="${'$'}{y - 4}" font-size="11" font-weight="bold" fill="${'$'}{tColor}" text-anchor="middle" font-family="monospace">${'$'}{d.count}</text>
            </g>
          `;

          labelsSvg += `
            <text x="${'$'}{i * slotWidth + slotWidth / 2}" y="${'$'}{height - 8}" font-size="10" fill="${'$'}{mColor}" text-anchor="middle">${'$'}{d.bin}</text>
          `;
        });
      }

      container.innerHTML = `
        <svg width="100%" height="100%" viewBox="0 0 ${'$'}{width} ${'$'}{height}">
          <!-- Grid lines -->
          <line x1="10" y1="${'$'}{height - paddingBottom}" x2="${'$'}{width - 10}" y2="${'$'}{height - paddingBottom}" stroke="${'$'}{gColor}" stroke-width="1" />
          <line x1="10" y1="${'$'}{height - paddingBottom - chartHeight / 2}" x2="${'$'}{width - 10}" y2="${'$'}{height - paddingBottom - chartHeight / 2}" stroke="${'$'}{gColor}" stroke-width="1" stroke-dasharray="3,3" />
          <line x1="10" y1="${'$'}{paddingTop}" x2="${'$'}{width - 10}" y2="${'$'}{paddingTop}" stroke="${'$'}{gColor}" stroke-width="1" stroke-dasharray="3,3" />

          <!-- Bars -->
          ${'$'}{barsSvg}

          <!-- Labels -->
          ${'$'}{labelsSvg}
        </svg>
      `;
    }

    window.updateRechartsData = function(mode, topK, bins, isDark, primary, text, muted, grid) {
      currentMode = mode || currentMode;
      currentTopK = topK || currentTopK;
      currentBins = bins || currentBins;
      isDarkTheme = isDark;
      pColor = primary || pColor;
      tColor = text || tColor;
      mColor = muted || mColor;
      gColor = grid || gColor;
      renderRechartsComponent();
    };

    // Initial render
    window.addEventListener('load', renderRechartsComponent);
    setTimeout(renderRechartsComponent, 50);
  </script>
</body>
</html>
""".trimIndent()
}
