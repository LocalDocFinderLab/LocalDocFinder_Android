package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Custom Compose Empty State Illustration screen displayed when no documents have been indexed yet into SQLite FTS / Vector engine.
 *
 * Provides a custom-drawn glowing Canvas graphic and clear calls-to-action to scan local folders, index Downloads, or pick files.
 */
@Composable
fun EmptyIndexStateIllustration(
    onScanFoldersClick: () -> Unit,
    onIndexDownloadsClick: () -> Unit,
    onLoadSamplesClick: () -> Unit,
    onPickFilesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    // Scanner animation pulse
    val scanPulse = remember { Animatable(0.2f) }
    val rotationAnim = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scanPulse.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    }

    LaunchedEffect(Unit) {
        rotationAnim.animateTo(
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 18000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("empty_state_illustration_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 1. Custom Vector Canvas Graphic Illustration
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .testTag("empty_state_canvas_illustration"),
                contentAlignment = Alignment.Center
            ) {
                // Background radial glow canvas
                Canvas(modifier = Modifier.size(200.dp)) {
                    val centerPx = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width / 2f

                    // Outer gradient halo
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.25f * scanPulse.value),
                                secondaryColor.copy(alpha = 0.12f * scanPulse.value),
                                Color.Transparent
                            ),
                            center = centerPx,
                            radius = radius
                        ),
                        radius = radius,
                        center = centerPx
                    )

                    // Dashed radar / vector search grid ring
                    rotate(rotationAnim.value) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.35f),
                            radius = radius * 0.78f,
                            center = centerPx,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                            )
                        )
                    }

                    // Concentric scanner wave
                    drawCircle(
                        color = tertiaryColor.copy(alpha = 0.20f * (1f - scanPulse.value)),
                        radius = radius * 0.55f * scanPulse.value + 20f,
                        center = centerPx,
                        style = Stroke(width = 3.dp.toPx())
                    )

                    // Folder / Document vector backdrop shape
                    val folderWidth = 90.dp.toPx()
                    val folderHeight = 65.dp.toPx()
                    val folderLeft = centerPx.x - folderWidth / 2f
                    val folderTop = centerPx.y - folderHeight / 2f + 8f

                    // Folder shadow/glow
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.18f),
                        topLeft = Offset(folderLeft - 6f, folderTop - 6f),
                        size = Size(folderWidth + 12f, folderHeight + 12f),
                        cornerRadius = CornerRadius(16.dp.toPx())
                    )

                    // Folder body
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.85f),
                                secondaryColor.copy(alpha = 0.70f)
                            )
                        ),
                        topLeft = Offset(folderLeft, folderTop),
                        size = Size(folderWidth, folderHeight),
                        cornerRadius = CornerRadius(12.dp.toPx())
                    )

                    // Folder tab
                    val tabPath = Path().apply {
                        moveTo(folderLeft + 12.dp.toPx(), folderTop)
                        lineTo(folderLeft + 32.dp.toPx(), folderTop - 12.dp.toPx())
                        lineTo(folderLeft + 52.dp.toPx(), folderTop - 12.dp.toPx())
                        lineTo(folderLeft + 60.dp.toPx(), folderTop)
                        close()
                    }
                    drawPath(
                        path = tabPath,
                        color = primaryColor.copy(alpha = 0.90f)
                    )

                    // Empty document floating card out of folder
                    val docLeft = folderLeft + 15.dp.toPx()
                    val docTop = folderTop - 25.dp.toPx()
                    val docWidth = 60.dp.toPx()
                    val docHeight = 70.dp.toPx()

                    drawRoundRect(
                        color = surfaceVariant,
                        topLeft = Offset(docLeft, docTop),
                        size = Size(docWidth, docHeight),
                        cornerRadius = CornerRadius(8.dp.toPx())
                    )

                    // Vector text placeholder lines inside document
                    val lineX = docLeft + 10.dp.toPx()
                    val lineW = docWidth - 20.dp.toPx()
                    val lineY1 = docTop + 14.dp.toPx()
                    val lineY2 = docTop + 24.dp.toPx()
                    val lineY3 = docTop + 34.dp.toPx()

                    drawLine(
                        color = primaryColor,
                        start = Offset(lineX, lineY1),
                        end = Offset(lineX + lineW * 0.8f, lineY1),
                        strokeWidth = 3.dp.toPx()
                    )
                    drawLine(
                        color = secondaryColor.copy(alpha = 0.7f),
                        start = Offset(lineX, lineY2),
                        end = Offset(lineX + lineW * 0.6f, lineY2),
                        strokeWidth = 2.5.dp.toPx()
                    )
                    drawLine(
                        color = primaryColor.copy(alpha = 0.5f),
                        start = Offset(lineX, lineY3),
                        end = Offset(lineX + lineW * 0.9f, lineY3),
                        strokeWidth = 2.5.dp.toPx()
                    )
                }

                // Foreground overlay icon badge
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DocumentScanner,
                        contentDescription = "Scanner",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Headline Title & Subtitle
            Text(
                text = "No Documents Indexed Yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Scan your local folders or select specific document files to enable zero-latency hybrid vector & FTS keyword search completely offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Primary Call to Action Button: Scan Local Folders
            Button(
                onClick = onScanFoldersClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("cta_scan_folders_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Scan Local Folders",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Secondary Quick Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quick Downloads Indexer CTA
                FilledTonalButton(
                    onClick = onIndexDownloadsClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cta_index_downloads_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Downloads",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Pick Specific Files CTA
                OutlinedButton(
                    onClick = onPickFilesClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cta_select_files_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Select Files",
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Load Sample Knowledge Base CTA
            OutlinedButton(
                onClick = onLoadSamplesClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cta_load_samples_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Load Sample Knowledge Base (10 Papers)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
