package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentPathEntity

/**
 * Lists the document and folder paths the user picked with the system file picker and that are stored
 * in Room, with their indexing status. Failed or pending paths can be retried; any path can be removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportedDocumentsSheet(
    paths: List<DocumentPathEntity>,
    onDismiss: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onRetryUnfinished: () -> Unit,
    onRemove: (DocumentPathEntity) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val unfinished = paths.count { it.status != "INDEXED" }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .testTag("imported_documents_sheet")
        ) {
            Text(
                text = "Imported documents",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Paths you picked are stored on this device. PDFBox reads each one in the background and adds its text to the search index.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFiles, modifier = Modifier.testTag("imported_pick_files")) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add files")
                }
                OutlinedButton(onClick = onPickFolder, modifier = Modifier.testTag("imported_pick_folder")) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add folder")
                }
                if (unfinished > 0) {
                    OutlinedButton(onClick = onRetryUnfinished, modifier = Modifier.testTag("imported_retry")) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry ($unfinished)")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (paths.isEmpty()) {
                Text(
                    text = "Nothing imported yet. Use Add files or Add folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(paths, key = { it.id }) { entry ->
                        ImportedPathRow(entry = entry, onRemove = { onRemove(entry) })
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImportedPathRow(entry: DocumentPathEntity, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isTreeUri) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName.ifBlank { "Document" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.isTreeUri) "Folder" else formatSize(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusChip(entry.status)
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove from list", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        "INDEXED" -> "Indexed" to Color(0xFF16A34A)
        "FAILED" -> "Failed" to Color(0xFFDC2626)
        else -> "Pending" to Color(0xFFF59E0B)
    }
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "Size unknown"
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(java.util.Locale.US, "%d KB", maxOf(1L, bytes / 1024L))
}
