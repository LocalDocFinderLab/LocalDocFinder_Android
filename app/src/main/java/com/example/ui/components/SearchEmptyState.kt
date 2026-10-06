package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.SearchMode

/**
 * Friendly empty state for the search result list, shown when a query matches no documents.
 *
 * Draws a small illustration (a stack of pages with a magnifier hovering over them) and lists
 * context-aware tips: the mode tip only appears when the user is not already in Hybrid mode, the
 * filter tip only when filters are active, and so on.
 *
 * @param query the query that produced no matches
 * @param searchMode the active search mode
 * @param hasActiveFilters whether file type / confidence / date / tag filters are narrowing the search
 * @param onSwitchToHybrid switches to Hybrid mode (button hidden when already hybrid)
 * @param onClearFilters clears the query and filters
 * @param onAddDocuments opens the indexing menu (button hidden when null)
 */
@Composable
fun SearchEmptyState(
    query: String,
    searchMode: SearchMode,
    modifier: Modifier = Modifier,
    hasActiveFilters: Boolean = false,
    onSwitchToHybrid: () -> Unit = {},
    onClearFilters: () -> Unit = {},
    onAddDocuments: (() -> Unit)? = null
) {
    val tips = buildList {
        add("Check the spelling, or try fewer and more general keywords.")
        if (searchMode != SearchMode.HYBRID) {
            add("Switch to Hybrid mode — it also matches by meaning, so different wording still finds the document.")
        } else {
            add("Describe the idea in a short phrase, e.g. \"how to reset my router\".")
        }
        if (hasActiveFilters) {
            add("Remove file type, date or tag filters — they may be hiding matches.")
        }
        add("Make sure the document is indexed. Add folders or files from the ☰ menu.")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("search_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NoResultsIllustration()

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (query.isNotBlank()) "No documents match \"$query\"" else "No documents match",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Nothing found yet — here are a few things to try.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_empty_state_tips"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Search tips",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                tips.forEach { tip ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "•",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(
                            text = tip,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searchMode != SearchMode.HYBRID) {
                FilledTonalButton(
                    onClick = onSwitchToHybrid,
                    modifier = Modifier.testTag("btn_empty_state_switch_hybrid")
                ) {
                    Text("Switch to Hybrid", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = onClearFilters,
                modifier = Modifier.testTag("btn_empty_state_clear")
            ) {
                Text(if (hasActiveFilters) "Clear filters" else "Clear search", fontSize = 12.sp)
            }
        }
        if (onAddDocuments != null) {
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.material3.TextButton(
                onClick = onAddDocuments,
                modifier = Modifier.testTag("btn_empty_state_add_documents")
            ) {
                Text("Add documents", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Hand-drawn "no results" illustration: two tilted pages, a front page with text lines and a
 * magnifier that gently floats above them, with a crossed-out lens.
 */
@Composable
private fun NoResultsIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val paper = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.outlineVariant

    val transition = rememberInfiniteTransition(label = "no_results_illustration")
    val bob by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "magnifier_bob"
    )

    Canvas(
        modifier = modifier
            .size(width = 190.dp, height = 140.dp)
            .semantics { contentDescription = "Illustration of a magnifying glass finding no documents" }
    ) {
        val w = size.width
        val h = size.height

        // Soft background glow
        drawCircle(
            color = primary.copy(alpha = 0.10f),
            radius = h * 0.48f,
            center = Offset(w * 0.46f, h * 0.52f)
        )

        // Back pages
        rotate(degrees = -10f, pivot = Offset(w * 0.36f, h * 0.6f)) {
            drawPage(Offset(w * 0.16f, h * 0.16f), Size(w * 0.36f, h * 0.6f), paper, outline, line, lines = 3)
        }
        rotate(degrees = 7f, pivot = Offset(w * 0.5f, h * 0.6f)) {
            drawPage(Offset(w * 0.3f, h * 0.2f), Size(w * 0.36f, h * 0.6f), paper, outline, line, lines = 3)
        }
        // Front page with an accent title bar
        drawPage(Offset(w * 0.22f, h * 0.28f), Size(w * 0.38f, h * 0.62f), paper, outline, line, lines = 4)
        drawRoundRect(
            color = secondary.copy(alpha = 0.55f),
            topLeft = Offset(w * 0.26f, h * 0.34f),
            size = Size(w * 0.16f, 5.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx())
        )

        // Floating magnifier
        val lensRadius = h * 0.2f
        val lensCenter = Offset(w * 0.62f, h * 0.46f + bob * 4.dp.toPx())
        val strokeW = 5.dp.toPx()
        drawCircle(color = paper.copy(alpha = 0.85f), radius = lensRadius, center = lensCenter)
        drawCircle(color = primary, radius = lensRadius, center = lensCenter, style = Stroke(width = strokeW))

        val dir = 0.7071f
        val handleStart = Offset(lensCenter.x + lensRadius * dir, lensCenter.y + lensRadius * dir)
        val handleEnd = Offset(handleStart.x + 24.dp.toPx() * dir, handleStart.y + 24.dp.toPx() * dir)
        drawLine(
            color = primary,
            start = handleStart,
            end = handleEnd,
            strokeWidth = strokeW + 1.dp.toPx(),
            cap = StrokeCap.Round
        )

        // "No match" cross inside the lens
        val cross = lensRadius * 0.38f
        drawLine(
            color = tertiary,
            start = Offset(lensCenter.x - cross, lensCenter.y - cross),
            end = Offset(lensCenter.x + cross, lensCenter.y + cross),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = tertiary,
            start = Offset(lensCenter.x + cross, lensCenter.y - cross),
            end = Offset(lensCenter.x - cross, lensCenter.y + cross),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Sparkle dots
        drawCircle(secondary.copy(alpha = 0.7f), radius = 3.dp.toPx(), center = Offset(w * 0.9f, h * 0.18f))
        drawCircle(primary.copy(alpha = 0.5f), radius = 2.dp.toPx(), center = Offset(w * 0.08f, h * 0.34f))
        drawCircle(tertiary.copy(alpha = 0.6f), radius = 2.5.dp.toPx(), center = Offset(w * 0.86f, h * 0.82f))
    }
}

private fun DrawScope.drawPage(
    topLeft: Offset,
    pageSize: Size,
    fill: Color,
    border: Color,
    textLine: Color,
    lines: Int
) {
    val corner = CornerRadius(6.dp.toPx())
    drawRoundRect(color = fill, topLeft = topLeft, size = pageSize, cornerRadius = corner)
    drawRoundRect(
        color = border.copy(alpha = 0.6f),
        topLeft = topLeft,
        size = pageSize,
        cornerRadius = corner,
        style = Stroke(width = 1.5.dp.toPx())
    )
    val pad = 8.dp.toPx()
    val lineHeight = 4.dp.toPx()
    val gap = 9.dp.toPx()
    val firstY = topLeft.y + pad + 12.dp.toPx()
    for (i in 0 until lines) {
        val widthFactor = if (i == lines - 1) 0.5f else 1f
        drawRoundRect(
            color = textLine,
            topLeft = Offset(topLeft.x + pad, firstY + i * gap),
            size = Size((pageSize.width - pad * 2) * widthFactor, lineHeight),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
    }
}
