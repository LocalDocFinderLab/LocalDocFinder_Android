package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Release highlights for the installed version, shown once after an install or update. */
object WhatsNew {

    data class Item(val title: String, val description: String)

    val features: List<Item> = listOf(
        Item(
            "See what your phone is made of",
            "Menu → Hardware & Thermal HUD now has a \"This device\" card: your processor, CPU cores and speed, GPU and its OpenGL / Vulkan version, and your NPU / TPU (Google Tensor is recognised by generation)."
        ),
        Item(
            "One dev build for every phone",
            "Testers get a single universal Dev build instead of picking a processor type, and can try every feature in it side by side with the regular app."
        )
    )

    val development: List<String> = listOf(
        "Dev build is now released as a single universal APK (no per-ABI dev APKs); the updater falls back to it automatically.",
        "engine/DeviceHardwareInfo reads SoC (Build.SOC_*), CPU max frequency, GL renderer/version via an offscreen EGL context, Vulkan level, TFLite GPU support and NPU/TPU; shown in HardwareDashboardSheet in all builds.",
        "Two builds per release: prod (LocalDocFinder-<tag>-<abi>.apk) and one universal dev build (LocalDocFinder-<tag>-dev-universal.apk, application id suffix .dev, installs next to prod). BuildConfig.DEV_MODE switches the dev-only UI.",
        "Dev build: Developer console (network ledger vs Android's own traffic count, app events + system log, app state, sample-data tools).",
        "privacy/NetworkGuard: every HTTP client is HTTPS-only, GET/HEAD-only with no request body, and limited to huggingface.co, hf.co, github.com and githubusercontent.com. Refusals are counted.",
        "The in-app updater only offers the APK of its own build type (prod updates to prod, dev to dev).",
        "Removed the unused RECORD_AUDIO permission and the WebView/CDN chart."
    )
}

@Composable
fun WhatsNewDialog(
    versionName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("What's new in $versionName") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("whats_new_content"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WhatsNew.features.forEach { item ->
                    Column {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.size(2.dp))
                Text(
                    text = "Under the hood",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                WhatsNew.development.forEach { line ->
                    Row {
                        Text("•", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(end = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("whats_new_done")) {
                Text("Got it")
            }
        }
    )
}
