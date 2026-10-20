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
            "Much smaller download",
            "Each release now has an APK for your phone's processor type, with unused code removed. In-app updates pick the right one automatically."
        ),
        Item(
            "Find files by name",
            "Typing a file's name finds it first, even with typos or glued-together names like Q3_TaxReport2023.pdf."
        ),
        Item(
            "Better ranking",
            "All your words beat some of them, the exact phrase beats scattered words, and unrelated \"similar meaning\" results no longer fill the list. Each file shows once."
        ),
        Item(
            "See why it matched",
            "Every result says whether it matched the file name, the text, text in a picture or similar meaning, and on which PDF page."
        ),
        Item(
            "Text in pictures",
            "Photos, screenshots, receipts and scanned PDFs are searchable by the text in them. Recognition runs on your phone; nothing is uploaded."
        ),
        Item(
            "Word, Excel and PowerPoint",
            "Old .doc, .xls and .ppt files and .xlsx spreadsheets are now read properly, including speaker notes and formula results."
        ),
        Item(
            "Run indexing once",
            "Tap Start indexing after updating so pictures, scans and Office files indexed before are read again."
        )
    )

    val development: List<String> = listOf(
        "Release builds are shrunk with R8 and split per processor type (arm64-v8a, armeabi-v7a, x86_64) plus a universal APK; CI smoke-tests the shrunk APK on an emulator.",
        "New search ranker (engine/search): file-name matching, whole-word BM25 with phrase and coverage scoring, calibrated vector scores, one result per file.",
        "New Office readers (engine/office) for .doc, .xls, .xlsx, .ppt and RTF, without Apache POI.",
        "On-device OCR with ML Kit's bundled text recognizer for images and scanned PDFs.",
        "No database schema change; chunk metadata flags mark files that need one re-read."
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
