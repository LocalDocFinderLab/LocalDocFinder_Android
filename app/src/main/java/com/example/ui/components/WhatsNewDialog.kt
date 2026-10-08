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
            "Highlighted matches",
            "Search results now show the words you searched for in bold with a coloured background, right inside the excerpt."
        ),
        Item(
            "Pick files and folders",
            "Documents and folders you choose with the system picker are saved on your device, with a status for each, and can be retried or removed under Imported Documents."
        ),
        Item(
            "Background text extraction",
            "Picked files are read with PDFBox in a background task that fills the full-text search index, so you can keep using the app or close it."
        ),
        Item(
            "Labels and filters",
            "Tag documents with built-in labels (Work, Personal, Finance, Legal, Medical, Education, Receipts, Travel, Research) or your own, then filter search results by label."
        ),
        Item(
            "Fixed update checks",
            "In-app updates now look at this project's GitHub releases and show the release notes."
        )
    )

    val development: List<String> = listOf(
        "Room database moved to version 6 with a document_paths table (no data loss on upgrade).",
        "New PdfBoxIndexWorker (WorkManager) extracts text with PDFBox and writes chunks that the SQLite FTS4 index picks up automatically.",
        "Default embedding model is now BGE Small v1.5.",
        "Debug APKs are built and attached to GitHub releases by a CI workflow."
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
