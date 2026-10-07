package com.example.ui.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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

data class FileTypeGroup(
    val id: String,
    val title: String,
    val description: String,
    val extensions: Set<String>,
    val icon: ImageVector,
    val iconColor: Color
)

val DEFAULT_FILE_TYPE_GROUPS = listOf(
    FileTypeGroup(
        id = "pdf",
        title = "PDF Documents",
        description = ".pdf files (research papers, contracts, invoices)",
        extensions = setOf("pdf"),
        icon = Icons.Default.PictureAsPdf,
        iconColor = Color(0xFFEF4444)
    ),
    FileTypeGroup(
        id = "office",
        title = "Office & Word Documents",
        description = ".docx, .doc, .pptx, .xlsx",
        extensions = setOf("docx", "doc", "pptx", "ppt", "xlsx", "xls"),
        icon = Icons.Default.Description,
        iconColor = Color(0xFF3B82F6)
    ),
    FileTypeGroup(
        id = "text_md",
        title = "Markdown & Plain Text",
        description = ".md, .txt, .markdown, .rst, .log",
        extensions = setOf("md", "txt", "markdown", "text", "rst", "log"),
        icon = Icons.Default.TextSnippet,
        iconColor = Color(0xFF10B981)
    ),
    FileTypeGroup(
        id = "code_data",
        title = "Code, JSON & Data",
        description = ".json, .csv, .tsv, .xml, .html",
        extensions = setOf("json", "csv", "tsv", "xml", "html", "htm"),
        icon = Icons.Default.Code,
        iconColor = Color(0xFF8B5CF6)
    ),
    FileTypeGroup(
        id = "images",
        title = "Images (with OCR/Metadata)",
        description = ".jpg, .png, .webp, .heic",
        extensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif"),
        icon = Icons.Default.Image,
        iconColor = Color(0xFFF59E0B)
    )
)

@Composable
fun FileTypeSelectionDialog(
    onDismiss: () -> Unit,
    onConfirmIndex: (selectedExtensions: Set<String>) -> Unit
) {
    var selectedGroups by remember {
        mutableStateOf(DEFAULT_FILE_TYPE_GROUPS.map { it.id }.toSet())
    }

    val selectedExtensions = remember(selectedGroups) {
        DEFAULT_FILE_TYPE_GROUPS
            .filter { it.id in selectedGroups }
            .flatMap { it.extensions }
            .toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Index Storage by File Types",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Choose which file types to scan and index across your device storage. Only files matching your selected formats will be embedded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            selectedGroups = DEFAULT_FILE_TYPE_GROUPS.map { it.id }.toSet()
                        }
                    ) {
                        Text("Select All", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            selectedGroups = emptySet()
                        }
                    ) {
                        Text("Clear All", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                DEFAULT_FILE_TYPE_GROUPS.forEach { group ->
                    val isChecked = group.id in selectedGroups
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedGroups = if (isChecked) {
                                    selectedGroups - group.id
                                } else {
                                    selectedGroups + group.id
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedGroups = if (checked) {
                                        selectedGroups + group.id
                                    } else {
                                        selectedGroups - group.id
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = group.icon,
                                contentDescription = null,
                                tint = group.iconColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = group.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmIndex(selectedExtensions)
                    onDismiss()
                },
                enabled = selectedExtensions.isNotEmpty(),
                modifier = Modifier.testTag("btn_confirm_file_type_index")
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Index Selected Types (${selectedExtensions.size})", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
