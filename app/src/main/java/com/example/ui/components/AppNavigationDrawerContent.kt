package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.ExecutionBackend
import com.example.engine.HardwareMetrics
import com.example.engine.SearchSortOrder
import com.example.updater.model.AppUpdateInfo

@Composable
fun AppNavigationDrawerContent(
    totalFiles: Int,
    totalChunks: Int,
    backend: ExecutionBackend,
    hardwareMetrics: HardwareMetrics,
    isGamingModePaused: Boolean,
    includeChatBackups: Boolean,
    activeAvailableUpdate: AppUpdateInfo?,
    isDarkTheme: Boolean,
    activeEmbeddingModel: com.example.engine.model.EmbeddingModelType = com.example.engine.model.EmbeddingModelType.DEFAULT,
    currentSortOrder: SearchSortOrder = SearchSortOrder.RELEVANCE,
    onSelectSortOrder: (SearchSortOrder) -> Unit = {},
    onOpenModelSheet: () -> Unit = {},
    onPickFolderClick: () -> Unit,
    onIndexDownloadsClick: () -> Unit,
    onIndexAndroidClick: () -> Unit,
    onIndexEntireStorageClick: () -> Unit,
    onPickDocumentClick: () -> Unit,
    onPickFilesClick: () -> Unit,
    onLoadSampleClick: () -> Unit,
    onLoad100SamplesClick: () -> Unit,
    onSeedTestDocumentsClick: () -> Unit,
    onScanMonitoredFolderClick: () -> Unit,
    onOpenPixelOptimizer: () -> Unit,
    onOpenHardwareDashboard: () -> Unit,
    onToggleGamingMode: () -> Unit,
    onOpenChatBackupSheet: () -> Unit,
    onToggleChatBackups: (Boolean) -> Unit,
    onOpenUpdateSheet: () -> Unit,
    onOpenGlossyOverlay: () -> Unit,
    onToggleTheme: () -> Unit,
    onClearDataClick: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(
        modifier = modifier
            .width(330.dp)
            .fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // 1. Drawer Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_localdoc_symbol),
                                        contentDescription = "Logo",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "LocalDoc Finder",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Offline Vector Search Engine",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = onCloseDrawer,
                            modifier = Modifier.testTag("btn_close_drawer")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Menu",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Library Stats Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Active Index",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$totalFiles docs • $totalChunks chunks",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = backend.tag,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Embedding & Indexing Model Selection
            DrawerSectionHeader(title = "Indexing & Search Model")

            DrawerMenuItem(
                icon = Icons.Default.AutoAwesome,
                title = activeEmbeddingModel.shortName,
                subtitle = "${activeEmbeddingModel.dimensions}-d • ${activeEmbeddingModel.accuracyRating}",
                onClick = {
                    onCloseDrawer()
                    onOpenModelSheet()
                },
                trailing = {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Change",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                testTag = "drawer_embedding_model_item"
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            // 3. Search Results Sorting Section (Dropdown Menu)
            DrawerSectionHeader(title = "Sort Search Results")

            var sortDropdownExpanded by remember { mutableStateOf(false) }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Sort Results By",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_sort_dropdown_container")
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { sortDropdownExpanded = !sortDropdownExpanded }
                                .testTag("drawer_sort_dropdown_trigger"),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val (icon, label) = when (currentSortOrder) {
                                        SearchSortOrder.RELEVANCE -> Pair(Icons.Default.AutoAwesome, "Relevance")
                                        SearchSortOrder.DATE_DESC -> Pair(Icons.Default.CalendarToday, "Date Modified")
                                        SearchSortOrder.FILE_SIZE -> Pair(Icons.Default.Storage, "File Size")
                                        SearchSortOrder.DATE_ASC -> Pair(Icons.Default.CalendarToday, "Date Modified (Oldest)")
                                        SearchSortOrder.NAME_ASC -> Pair(Icons.AutoMirrored.Filled.Sort, "File Name (A to Z)")
                                        SearchSortOrder.NAME_DESC -> Pair(Icons.AutoMirrored.Filled.Sort, "File Name (Z to A)")
                                    }
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = if (sortDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Toggle sort dropdown",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = sortDropdownExpanded,
                            onDismissRequest = { sortDropdownExpanded = false },
                            modifier = Modifier
                                .width(280.dp)
                                .testTag("drawer_sort_dropdown_menu")
                        ) {
                            // 1. Relevance
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "Relevance",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (currentSortOrder == SearchSortOrder.RELEVANCE) FontWeight.Bold else FontWeight.Normal,
                                            color = if (currentSortOrder == SearchSortOrder.RELEVANCE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "RRF & semantic similarity ranking",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = if (currentSortOrder == SearchSortOrder.RELEVANCE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = if (currentSortOrder == SearchSortOrder.RELEVANCE) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onSelectSortOrder(SearchSortOrder.RELEVANCE)
                                    sortDropdownExpanded = false
                                },
                                modifier = Modifier.testTag("drawer_sort_item_relevance")
                            )

                            // 2. Date Modified
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "Date Modified",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (currentSortOrder == SearchSortOrder.DATE_DESC) FontWeight.Bold else FontWeight.Normal,
                                            color = if (currentSortOrder == SearchSortOrder.DATE_DESC) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Most recently updated files first",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.CalendarToday,
                                        contentDescription = null,
                                        tint = if (currentSortOrder == SearchSortOrder.DATE_DESC) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = if (currentSortOrder == SearchSortOrder.DATE_DESC) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onSelectSortOrder(SearchSortOrder.DATE_DESC)
                                    sortDropdownExpanded = false
                                },
                                modifier = Modifier.testTag("drawer_sort_item_date_modified")
                            )

                            // 3. File Size
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = "File Size",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (currentSortOrder == SearchSortOrder.FILE_SIZE) FontWeight.Bold else FontWeight.Normal,
                                            color = if (currentSortOrder == SearchSortOrder.FILE_SIZE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Largest documents & chunks first",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = if (currentSortOrder == SearchSortOrder.FILE_SIZE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = if (currentSortOrder == SearchSortOrder.FILE_SIZE) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onSelectSortOrder(SearchSortOrder.FILE_SIZE)
                                    sortDropdownExpanded = false
                                },
                                modifier = Modifier.testTag("drawer_sort_item_file_size")
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            // 3. Folder & Document Indexing Section
            DrawerSectionHeader(title = "Folder & Document Indexing")

            DrawerMenuItem(
                icon = Icons.Default.Download,
                title = "Index Downloads Folder",
                subtitle = "Bypasses SAF restrictions to index downloads",
                onClick = {
                    onCloseDrawer()
                    onIndexDownloadsClick()
                },
                testTag = "drawer_index_downloads"
            )

            DrawerMenuItem(
                icon = Icons.Default.Folder,
                title = "Index Android Folder",
                subtitle = "Scans /Android & document media directories",
                onClick = {
                    onCloseDrawer()
                    onIndexAndroidClick()
                },
                testTag = "drawer_index_android"
            )

            DrawerMenuItem(
                icon = Icons.Default.DocumentScanner,
                title = "Scan Entire Storage",
                subtitle = "Auto-crawls all PDFs & documents across device",
                onClick = {
                    onCloseDrawer()
                    onIndexEntireStorageClick()
                },
                testTag = "drawer_index_entire_storage"
            )

            DrawerMenuItem(
                icon = Icons.Default.FolderOpen,
                title = "Select Folder (SAF)",
                subtitle = "Pick custom directory with persistable access",
                onClick = {
                    onCloseDrawer()
                    onPickFolderClick()
                },
                testTag = "drawer_pick_folder"
            )

            DrawerMenuItem(
                icon = Icons.Default.Tune,
                title = "Pick Specific Files",
                subtitle = "Ingest single or multiple documents directly",
                onClick = {
                    onCloseDrawer()
                    onPickFilesClick()
                },
                testTag = "drawer_pick_files"
            )

            DrawerMenuItem(
                icon = Icons.Default.PlayArrow,
                title = "Load Sample Knowledge Base",
                subtitle = "Loads AI, Distributed & Quantum technical papers",
                onClick = {
                    onCloseDrawer()
                    onLoadSampleClick()
                },
                testTag = "drawer_load_sample"
            )

            DrawerMenuItem(
                icon = Icons.Default.Refresh,
                title = "Load 100 Sample Files",
                subtitle = "Generates and embeds 100 test research papers",
                onClick = {
                    onCloseDrawer()
                    onLoad100SamplesClick()
                },
                testTag = "drawer_load_100_samples"
            )

            DrawerMenuItem(
                icon = Icons.Default.Sync,
                title = "Scan Monitored Folder",
                subtitle = "Checks auto-sync folder for newly created files",
                onClick = {
                    onCloseDrawer()
                    onScanMonitoredFolderClick()
                },
                testTag = "drawer_scan_monitored"
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            // 3. Hardware Acceleration & Gaming Mode Section
            DrawerSectionHeader(title = "Hardware & Neural Engine")

            DrawerMenuItem(
                icon = Icons.Default.Memory,
                title = "Pixel Tensor & TPU Settings",
                subtitle = "Thermal pacing, thread pinning & benchmarks",
                onClick = {
                    onCloseDrawer()
                    onOpenPixelOptimizer()
                },
                testTag = "drawer_pixel_optimizer"
            )

            DrawerMenuItem(
                icon = Icons.Default.Speed,
                title = "Hardware & Thermal HUD",
                subtitle = "${hardwareMetrics.cpuUsagePercent}% CPU • ${hardwareMetrics.availableCores} Cores • ${hardwareMetrics.memoryUsageMb} MB",
                onClick = {
                    onCloseDrawer()
                    onOpenHardwareDashboard()
                },
                testTag = "drawer_hardware_dashboard"
            )

            // Gaming Mode Switch Item
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggleGamingMode() }
                    .testTag("drawer_gaming_mode_toggle"),
                color = if (isGamingModePaused) Color(0xFFF59E0B).copy(alpha = 0.12f) else Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsEsports,
                            contentDescription = null,
                            tint = if (isGamingModePaused) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Gaming Mode (Pause)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isGamingModePaused) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isGamingModePaused) "Indexing paused to protect FPS" else "0% NPU load during gameplay",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isGamingModePaused,
                        onCheckedChange = { onToggleGamingMode() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFF59E0B),
                            checkedTrackColor = Color(0xFFF59E0B).copy(alpha = 0.4f)
                        )
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            // 4. Private Sources Section
            DrawerSectionHeader(title = "Private Data Sources")

            DrawerMenuItem(
                icon = Icons.AutoMirrored.Filled.Message,
                title = "SMS & Chat Backups",
                subtitle = if (includeChatBackups) "Included in vector search" else "Excluded from vector search",
                onClick = {
                    onCloseDrawer()
                    onOpenChatBackupSheet()
                },
                trailing = {
                    Switch(
                        checked = includeChatBackups,
                        onCheckedChange = { onToggleChatBackups(it) }
                    )
                },
                testTag = "drawer_chat_backups"
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            // 5. Tools & App Settings Section
            DrawerSectionHeader(title = "Tools & Diagnostics")

            DrawerMenuItem(
                icon = Icons.Default.Bolt,
                title = "Glossy Search Overlay",
                subtitle = "Floating translucent home screen search bar",
                onClick = {
                    onCloseDrawer()
                    onOpenGlossyOverlay()
                },
                testTag = "drawer_glossy_overlay"
            )

            DrawerMenuItem(
                icon = Icons.Default.SystemUpdate,
                title = "Check for Updates (OTA)",
                subtitle = if (activeAvailableUpdate != null) "New v${activeAvailableUpdate.versionName} ready" else "Check GitHub / custom manifests",
                onClick = {
                    onCloseDrawer()
                    onOpenUpdateSheet()
                },
                trailing = if (activeAvailableUpdate != null) {
                    {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(8.dp)
                        ) {}
                    }
                } else null,
                testTag = "drawer_check_updates"
            )

            DrawerMenuItem(
                icon = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                title = if (isDarkTheme) "Light Theme" else "Dark Theme",
                subtitle = "Toggle app color scheme",
                onClick = onToggleTheme,
                testTag = "drawer_theme_toggle"
            )

            DrawerMenuItem(
                icon = Icons.Default.DeleteSweep,
                title = "Clear Document Index",
                subtitle = "Wipe all chunks & full-text SQLite tables",
                onClick = {
                    onCloseDrawer()
                    onClearDataClick()
                },
                iconTint = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
                testTag = "drawer_clear_database"
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    testTag: String = ""
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag(testTag),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}
