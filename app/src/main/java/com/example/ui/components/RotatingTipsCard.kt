package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class SearchTip(
    val title: String,
    val description: String,
    val sampleQuery: String? = null,
    val icon: ImageVector = Icons.Default.Lightbulb,
    val iconColor: Color = Color(0xFFF59E0B)
)

val DEFAULT_SEARCH_TIPS = listOf(
    SearchTip(
        title = "Natural Language Semantic Search",
        description = "Ask questions or search concepts in plain English. The AI matches meaning, not just exact keywords.",
        sampleQuery = "What are the mechanisms of mRNA vaccines?",
        icon = Icons.Default.AutoAwesome,
        iconColor = Color(0xFF6366F1)
    ),
    SearchTip(
        title = "Hybrid BM25 + Neural Embeddings",
        description = "Combines SQLite full-text search with local 384-d vector embeddings for ultra-fast, accurate matching.",
        sampleQuery = "LiteRT quantization delegate",
        icon = Icons.Default.Speed,
        iconColor = Color(0xFF10B981)
    ),
    SearchTip(
        title = "100% Private & On-Device",
        description = "Your documents and search queries never leave this device. Zero network telemetry or cloud API calls.",
        sampleQuery = null,
        icon = Icons.Default.Lock,
        iconColor = Color(0xFF06B6D4)
    ),
    SearchTip(
        title = "Deep Document & Image Indexing",
        description = "Search across PDFs, Word docs, Markdown, Code, JSON, and extracted images directly from your storage.",
        sampleQuery = "Distributed consensus Raft protocol",
        icon = Icons.Default.FolderOpen,
        iconColor = Color(0xFFF59E0B)
    ),
    SearchTip(
        title = "Filter & Sort by Confidence",
        description = "Use the filter chips to isolate High (>70%) confidence results or filter by date range and file extension.",
        sampleQuery = null,
        icon = Icons.Default.Search,
        iconColor = Color(0xFFEC4899)
    )
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RotatingTipsCard(
    tips: List<SearchTip> = DEFAULT_SEARCH_TIPS,
    onTipClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }

    LaunchedEffect(isPaused, currentIndex) {
        if (!isPaused && tips.isNotEmpty()) {
            delay(6000L) // Rotate every 6 seconds
            currentIndex = (currentIndex + 1) % tips.size
        }
    }

    val currentTip = tips.getOrNull(currentIndex) ?: return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("rotating_tips_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Tip badge & Navigation arrows
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = currentTip.iconColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = currentTip.icon,
                                contentDescription = null,
                                tint = currentTip.iconColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SEARCH TIP",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = currentTip.iconColor,
                        letterSpacing = 1.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            currentIndex = if (currentIndex > 0) currentIndex - 1 else tips.size - 1
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("btn_prev_tip")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Previous tip",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Dot indicators
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        tips.forEachIndexed { idx, _ ->
                            Box(
                                modifier = Modifier
                                    .size(if (idx == currentIndex) 8.dp else 5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (idx == currentIndex) currentTip.iconColor
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                    )
                                    .clickable { currentIndex = idx }
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            currentIndex = (currentIndex + 1) % tips.size
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("btn_next_tip")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next tip",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Animated Tip Content
            AnimatedContent(
                targetState = currentTip,
                transitionSpec = {
                    (slideInHorizontally { width -> width / 4 } + fadeIn()) with
                            (slideOutHorizontally { width -> -width / 4 } + fadeOut())
                },
                label = "tip_transition"
            ) { tip ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = tip.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tip.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    if (tip.sampleQuery != null && onTipClick != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onTipClick(tip.sampleQuery) }
                                .testTag("tip_sample_query_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Try: \"${tip.sampleQuery}\"",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
