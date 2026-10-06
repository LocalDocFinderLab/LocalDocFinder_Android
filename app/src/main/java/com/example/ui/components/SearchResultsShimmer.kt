package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Returns an animated shimmer [Brush] that sweeps a highlight band across the screen width.
 * One brush is shared by every placeholder in a list so the whole skeleton shimmers in sync.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val screenWidthPx = LocalContext.current.resources.displayMetrics.widthPixels.toFloat()
    val bandWidth = screenWidthPx * 0.6f

    val transition = rememberInfiniteTransition(label = "search_shimmer")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "search_shimmer_sweep"
    )

    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = remember(base, highlight) {
        listOf(
            base.copy(alpha = 0.35f),
            highlight.copy(alpha = 0.18f),
            base.copy(alpha = 0.35f)
        )
    }

    val startX = -bandWidth + sweep * (screenWidthPx + bandWidth)
    return Brush.linearGradient(
        colors = colors,
        start = Offset(startX, 0f),
        end = Offset(startX + bandWidth, bandWidth * 0.25f)
    )
}

/**
 * Skeleton of the search result list shown while the hybrid engine embeds the query,
 * scans the vector index and runs the SQLite FTS query.
 */
@Composable
fun SearchResultsShimmer(
    modifier: Modifier = Modifier,
    itemCount: Int = 5
) {
    val brush = rememberShimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("search_results_shimmer")
            .semantics { contentDescription = "Searching documents" },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Searching your documents…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        repeat(itemCount) {
            ShimmerResultCard(brush)
        }
    }
}

@Composable
private fun ShimmerResultCard(brush: Brush) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShimmerBlock(brush, Modifier.size(32.dp), RoundedCornerShape(8.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerBlock(brush, Modifier.fillMaxWidth(0.55f).height(12.dp))
                    ShimmerBlock(brush, Modifier.fillMaxWidth(0.35f).height(9.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                ShimmerBlock(brush, Modifier.width(54.dp).height(18.dp), RoundedCornerShape(9.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShimmerBlock(brush, Modifier.fillMaxWidth().height(10.dp))
                ShimmerBlock(brush, Modifier.fillMaxWidth().height(10.dp))
                ShimmerBlock(brush, Modifier.fillMaxWidth(0.7f).height(10.dp))
            }
        }
    }
}

@Composable
private fun ShimmerBlock(
    brush: Brush,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(6.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}
