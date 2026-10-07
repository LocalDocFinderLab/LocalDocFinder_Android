package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.example.engine.HardwareMetrics
import com.example.engine.IndexingPowerPolicy
import com.example.ui.IndexingState

/**
 * A beautiful Material 3 card displaying the status of the offline document index.
 * Collapsible: shows a minimal single-row summary when collapsed, and full detailed
 * controls, speed bar, and progress when expanded.
 */
@Composable
fun IndexingStatusCard(
    totalFiles: Int,
    totalChunks: Int,
    indexingState: IndexingState,
    onStopIndexingClick: () -> Unit,
    modifier: Modifier = Modifier,
    isIndexingActive: Boolean = indexingState is IndexingState.Progress,
    isStoppedByUser: Boolean = false,
    onStartIndexingClick: (() -> Unit)? = null,
    autoScanEnabled: Boolean = true,
    onToggleAutoScan: ((Boolean) -> Unit)? = null,
    lastScanMessage: String? = null,
    speed: IndexingPowerPolicy.Speed? = null,
    fullSpeedEnabled: Boolean = false,
    onToggleFullSpeed: ((Boolean) -> Unit)? = null,
    isMinimized: Boolean? = null,
    onToggleMinimized: (() -> Unit)? = null,
    metrics: HardwareMetrics? = null
) {
    var internalMinimized by remember { mutableStateOf(false) }
    val effectivelyMinimized = isMinimized ?: internalMinimized
    val toggleAction = onToggleMinimized ?: { internalMinimized = !internalMinimized }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("indexing_status_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (effectivelyMinimized) 12.dp else 16.dp)
        ) {
            // Header Row: Status icon and index stats (clickable to expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { toggleAction() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val statusIcon = if (indexingState is IndexingState.Progress) {
                        Icons.Default.PendingActions
                    } else {
                        Icons.Default.CheckCircle
                    }
                    val statusIconColor = if (indexingState is IndexingState.Progress) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color(0xFF10B981)
                    }

                    Icon(
                        imageVector = statusIcon,
                        contentDescription = "Indexing Status Icon",
                        tint = statusIconColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Local Search Index",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (indexingState is IndexingState.Progress) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.testTag("indexing_percentage_badge")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(9.dp),
                                            strokeWidth = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${indexingState.percent}%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = when {
                                indexingState is IndexingState.Progress -> "Indexing ${indexingState.current}/${indexingState.total} (${indexingState.percent}%) • ${indexingState.currentFile}"
                                isIndexingActive -> "Scanning for new files…"
                                isStoppedByUser -> "Indexing stopped"
                                else -> "All files scanned & ready"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.testTag("indexing_status_text")
                        )
                    }
                }

                // Summary Files count + Minimize Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "$totalFiles Files Indexed",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!effectivelyMinimized) {
                            Text(
                                text = "$totalChunks Vector Chunks",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    IconButton(
                        onClick = toggleAction,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("btn_toggle_minimize_indexing_card")
                    ) {
                        Icon(
                            imageVector = if (effectivelyMinimized) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (effectivelyMinimized) "Expand index card" else "Minimize index card",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Minimal progress bar when collapsed and active
            if (effectivelyMinimized && indexingState is IndexingState.Progress) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = indexingState.currentFile,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${indexingState.percent}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { indexingState.percent.toFloat() / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Expanded Controls & Details
            AnimatedVisibility(
                visible = !effectivelyMinimized,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (indexingState is IndexingState.Progress) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp)
                        ) {
                            // Current progress text and Cancel/Stop button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Indexing ${indexingState.current}/${indexingState.total}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = indexingState.currentFile,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.testTag("indexing_current_file")
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${indexingState.percent}%",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = onStopIndexingClick,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("btn_stop_indexing_card")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Cancel Indexing",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Linear progress bar
                            LinearProgressIndicator(
                                progress = { indexingState.percent.toFloat() / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .testTag("indexing_progress_bar"),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            // Speed bar
                            if (speed != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                IndexingSpeedBar(speed = speed)
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Ingestion details phase
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Phase Info",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = indexingState.currentPhase,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("indexing_phase_text")
                                )
                            }
                        }
                    }

                    // Real-time telemetry metrics row (CPU %, RAM MB, Latency, Cores)
                    metrics?.let { m ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "CPU ${m.cpuUsagePercent}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = Color(0xFF10B981)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "RAM ${m.memoryUsageMb}MB",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = Color(0xFFF59E0B)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${m.availableCores} Cores",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${m.inferenceLatencyMs}ms",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (onStartIndexingClick != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        if (isIndexingActive) {
                            FilledTonalButton(
                                onClick = onStopIndexingClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_stop_indexing"),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Stop indexing", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = onStartIndexingClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_start_indexing")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isStoppedByUser) "Start indexing" else "Scan & index now",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (isStoppedByUser) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Nothing runs in the background until you start indexing again.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (onToggleAutoScan != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-scan for new files",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = when {
                                        isStoppedByUser -> "Off while indexing is stopped."
                                        autoScanEnabled -> "Checks Downloads & folders every 15 min. ${lastScanMessage.orEmpty()}".trim()
                                        else -> "Off. New files are only indexed when you start indexing."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("auto_scan_description")
                                )
                            }
                            Switch(
                                checked = autoScanEnabled && !isStoppedByUser,
                                onCheckedChange = onToggleAutoScan,
                                enabled = !isStoppedByUser,
                                modifier = Modifier.testTag("switch_auto_scan")
                            )
                        }
                    }

                    if (onToggleFullSpeed != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Full speed",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (fullSpeedEnabled) {
                                        "On: indexing uses all cores even on battery or while you use the phone. Turn off when the first big run is done."
                                    } else {
                                        "Best for the first indexing of lots of documents. Off by default to save battery."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("full_speed_description")
                                )
                            }
                            Switch(
                                checked = fullSpeedEnabled,
                                onCheckedChange = onToggleFullSpeed,
                                modifier = Modifier.testTag("switch_full_speed")
                            )
                        }
                    }
                }
            }
        }
    }
}
