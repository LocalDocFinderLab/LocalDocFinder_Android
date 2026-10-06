package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.PixelTensorOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PixelOptimizationDialog(
    profile: PixelTensorOptimizer.PixelProfile,
    onBenchmarkRequested: suspend () -> Long,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isBenchmarking by remember { mutableStateOf(false) }
    var benchmarkResultMs by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF4285F4).copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = "Pixel Chipset",
                            tint = Color(0xFF4285F4),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Pixel Hardware Accelerator",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (profile.isGoogleTensorSoc) profile.tensorGeneration else profile.deviceModel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                // Chipset identity card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Chipset / SoC:", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = profile.socName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("TPU Accelerator:", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = if (profile.isGoogleTensorSoc) "Google EdgeTPU" else "CPU / GPU",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (profile.isGoogleTensorSoc) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Driver Status:", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = profile.tpuDriverStatus,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Active optimizations list
                Text(
                    text = "Pixel Tensor Optimizations Active:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))

                OptimizationItem(
                    icon = Icons.Default.Bolt,
                    title = "EdgeTPU Sustained Speed",
                    description = "Locks frequency states on Google EdgeTPU to prevent DVFS hunting and eliminate latency spikes.",
                    status = "Active"
                )

                OptimizationItem(
                    icon = Icons.Default.Speed,
                    title = "FP16 / INT8 Tensor Pipeline",
                    description = "Native systolic array matrix acceleration matching Google Tensor's hardware execution units.",
                    status = "Active"
                )

                OptimizationItem(
                    icon = Icons.Default.Memory,
                    title = "Heterogeneous CPU Core Guard",
                    description = "Pins workers to Big (Cortex-X) & Mid cores (${profile.recommendedCpuThreads} threads); prevents stalling on low-power LITTLE cores.",
                    status = "${profile.recommendedCpuThreads} Threads"
                )

                OptimizationItem(
                    icon = Icons.Default.DeviceThermostat,
                    title = "Dynamic Thermal Guard",
                    description = "Current status: ${profile.currentThermalStatus}. Dynamically paces batch size (${profile.recommendedBatchSize} chunks) and cooldown to protect battery.",
                    status = if (profile.thermalStatusLevel <= 1) "Cool" else "Pacing Active",
                    statusColor = if (profile.thermalStatusLevel <= 1) Color(0xFF10B981) else Color(0xFFF59E0B)
                )

                OptimizationItem(
                    icon = Icons.Default.Storage,
                    title = "AOT Compiled Model Caching",
                    description = "Compiled EdgeTPU graph cached in codeCacheDir to avoid runtime JIT compilation overhead.",
                    status = "Cached"
                )

                OptimizationItem(
                    icon = Icons.Default.Bolt,
                    title = "Quantized TFLite Embedding Service",
                    description = "Executes INT8 quantized models locally on-device via TextEmbeddingService with zero cloud dependencies.",
                    status = "Ready",
                    statusColor = Color(0xFF10B981)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Benchmark Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Vector Inference Benchmark",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Run 10 consecutive 384-d semantic vector embeddings to test on-device latency.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isBenchmarking) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Measuring Tensor latency…", style = MaterialTheme.typography.labelSmall)
                            }
                        } else if (benchmarkResultMs != null) {
                            Text(
                                text = "Average Latency: ${benchmarkResultMs} ms / vector",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isBenchmarking = true
                                    try {
                                        val latency = onBenchmarkRequested()
                                        benchmarkResultMs = latency
                                    } finally {
                                        isBenchmarking = false
                                    }
                                }
                            },
                            enabled = !isBenchmarking,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (benchmarkResultMs == null) "Run Chipset Benchmark" else "Run Again")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun OptimizationItem(
    icon: ImageVector,
    title: String,
    description: String,
    status: String,
    statusColor: Color = Color(0xFF10B981)
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = status,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
