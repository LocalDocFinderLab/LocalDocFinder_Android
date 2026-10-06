package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.engine.DateRangePreset
import com.example.engine.SearchMode
import com.example.engine.SearchResult
import com.example.engine.SearchSortOrder
import com.example.engine.formatDateRangeLabel
import com.example.ui.components.AppNavigationDrawerContent
import com.example.ui.components.AppUpdateBanner
import com.example.ui.components.AppUpdateSheet
import com.example.ui.components.ChatBackupSheet
import com.example.ui.components.DocumentDetailSheet
import com.example.ui.components.BackgroundScanBanner
import com.example.ui.components.FtsDocumentSearchComponent
import com.example.ui.components.HardwareDashboardSheet
import com.example.ui.components.IndexingStatusCard
import com.example.ui.components.PixelOptimizationDialog
import com.example.ui.components.SearchFilterBottomSheet
import com.example.ui.components.SearchHistorySection
import com.example.ui.components.SearchResultCard
import com.example.ui.components.getFileTypeBadgeColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    autoFocusSearch: Boolean = false,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchMode by viewModel.searchMode.collectAsStateWithLifecycle()
    val rawResults by viewModel.rawSearchResults.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val selectedFileType by viewModel.selectedFileType.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val selectedConfidenceTier by viewModel.selectedConfidenceTier.collectAsStateWithLifecycle()
    val selectedDatePreset by viewModel.datePreset.collectAsStateWithLifecycle()
    val startDateMillis by viewModel.startDateMillis.collectAsStateWithLifecycle()
    val endDateMillis by viewModel.endDateMillis.collectAsStateWithLifecycle()
    val availableFileTypes by viewModel.availableFileTypes.collectAsStateWithLifecycle()
    val fileTypeCounts by viewModel.fileTypeCounts.collectAsStateWithLifecycle()
    val confidenceDistribution by viewModel.confidenceDistribution.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val latencyMs by viewModel.searchLatencyMs.collectAsStateWithLifecycle()
    val indexingState by viewModel.indexingState.collectAsStateWithLifecycle()
    val backend by viewModel.executionBackend.collectAsStateWithLifecycle()
    val totalChunks by viewModel.totalChunksCount.collectAsStateWithLifecycle()
    val totalFiles by viewModel.totalFilesCount.collectAsStateWithLifecycle()
    val selectedPreview by viewModel.selectedResultForPreview.collectAsStateWithLifecycle()
    val pixelProfile by viewModel.pixelProfile.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val hardwareMetrics by viewModel.hardwareMetrics.collectAsStateWithLifecycle()
    val indexingSpeed by viewModel.indexingSpeed.collectAsStateWithLifecycle()
    val isGamingModePaused by viewModel.isGamingModePaused.collectAsStateWithLifecycle()
    val includeChatBackups by viewModel.includeChatBackups.collectAsStateWithLifecycle()
    val chatIndexingProgress by viewModel.chatIndexingProgress.collectAsStateWithLifecycle()
    val isIndexingActive by viewModel.isIndexingActive.collectAsStateWithLifecycle()
    val isIndexingStoppedByUser by viewModel.isIndexingStoppedByUser.collectAsStateWithLifecycle()

    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsStateWithLifecycle()
    val selectedDocumentUris by viewModel.selectedDocumentUris.collectAsStateWithLifecycle()
    val folderMonitorStatus by viewModel.folderMonitorStatus.collectAsStateWithLifecycle()
    val fileObserverStatus by viewModel.fileObserverStatus.collectAsStateWithLifecycle()
    val vectorWeight by viewModel.vectorWeight.collectAsStateWithLifecycle()
    val bm25Weight by viewModel.bm25Weight.collectAsStateWithLifecycle()

    // Model Management
    val activeEmbeddingModel by viewModel.activeEmbeddingModel.collectAsStateWithLifecycle()
    val isGeminiConfigured by viewModel.isGeminiConfigured.collectAsStateWithLifecycle()
    val isReindexingModel by viewModel.isReindexingModel.collectAsStateWithLifecycle()
    val reindexingModelProgress by viewModel.reindexingModelProgress.collectAsStateWithLifecycle()
    val reindexingModelStatus by viewModel.reindexingModelStatus.collectAsStateWithLifecycle()
    val showModelSheet by viewModel.showModelSheet.collectAsStateWithLifecycle()

    // In-App Update Flows
    val updateCheckResult by viewModel.updateCheckResult.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val updateConfig by viewModel.updateConfig.collectAsStateWithLifecycle()
    val isCheckingForUpdates by viewModel.isCheckingForUpdates.collectAsStateWithLifecycle()
    val showUpdateSheet by viewModel.showUpdateSheet.collectAsStateWithLifecycle()
    val activeAvailableUpdate by viewModel.activeAvailableUpdate.collectAsStateWithLifecycle()

    val searchFocusRequester = remember { FocusRequester() }
    var showClearDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showPixelOptimizerDialog by remember { mutableStateOf(false) }
    var showFilterBottomSheet by remember { mutableStateOf(false) }
    var showSearchModeInfoDialog by remember { mutableStateOf(false) }
    var showHardwareDashboard by remember { mutableStateOf(false) }
    var showChatBackupSheet by remember { mutableStateOf(false) }

    var taggingFileUri by remember { mutableStateOf<String?>(null) }
    var taggingFileName by remember { mutableStateOf<String?>(null) }
    var taggingCurrentTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var newTagInput by remember { mutableStateOf("") }

    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val isDateFiltered = selectedDatePreset != DateRangePreset.ALL_TIME || startDateMillis != null || endDateMillis != null
    val activeFilterCount = (if (selectedFileType != null) 1 else 0) +
            (if (selectedConfidenceTier != null) 1 else 0) +
            (if (isDateFiltered) 1 else 0) +
            (if (sortOrder != SearchSortOrder.RELEVANCE) 1 else 0) +
            (if (selectedTag != null) 1 else 0)

    val isSearchActive = query.isNotBlank() || selectedTag != null

    BackHandler(enabled = isMultiSelectMode || drawerState.isOpen) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (isMultiSelectMode) {
            viewModel.toggleMultiSelectMode(false)
        }
    }

    LaunchedEffect(autoFocusSearch) {
        if (autoFocusSearch) {
            try {
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {}
        }
    }

    // SAF Document Tree Directory Picker
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                viewModel.setDirectoryUri(uri)
                Toast.makeText(context, "Indexing folder…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                viewModel.setDirectoryUri(uri)
            }
        }
    }

    // SAF Single Document Picker
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {}
            viewModel.processAndIngestDocument(uri) { isSuccess, fileName, chunks ->
                if (isSuccess) {
                    Toast.makeText(context, "Successfully indexed $fileName ($chunks chunks)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to index $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // SAF Multiple Document Picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            uris.forEach { u ->
                try {
                    context.contentResolver.takePersistableUriPermission(u, takeFlags)
                } catch (_: Exception) {}
            }
            viewModel.indexSelectedFiles(uris)
            Toast.makeText(context, "Indexing ${uris.size} selected file(s)…", Toast.LENGTH_SHORT).show()
        }
    }

    // Storage permission request launcher (READ_EXTERNAL_STORAGE / READ_MEDIA_*)
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            Toast.makeText(context, "Storage permissions granted! Initializing PDF crawler…", Toast.LENGTH_SHORT).show()
            viewModel.autoCrawlOnPermissionGranted()
        } else {
            Toast.makeText(context, "Storage permissions denied. PDF crawler directory access might be limited.", Toast.LENGTH_LONG).show()
        }
    }

    // Helper to check and request permissions before crawling
    fun requestStoragePermissionsAndCrawl(onGranted: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted || viewModel.hasAllFilesAccess()) {
            onGranted()
        } else {
            storagePermissionLauncher.launch(permissions)
        }
    }

    // Notification permission request for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (viewModel.hasAllFilesAccess()) {
                    viewModel.autoCrawlOnPermissionGranted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        // Request standard storage permissions if they are not already granted
        val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val storageGranted = storagePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!storageGranted && !viewModel.hasAllFilesAccess()) {
            storagePermissionLauncher.launch(storagePermissions)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppNavigationDrawerContent(
                totalFiles = totalFiles,
                totalChunks = totalChunks,
                backend = backend,
                hardwareMetrics = hardwareMetrics,
                isGamingModePaused = isGamingModePaused,
                includeChatBackups = includeChatBackups,
                activeAvailableUpdate = activeAvailableUpdate,
                isDarkTheme = isDarkTheme,
                activeEmbeddingModel = activeEmbeddingModel,
                currentSortOrder = sortOrder,
                onSelectSortOrder = { newSort ->
                    viewModel.setSortOrder(newSort)
                },
                onOpenModelSheet = { viewModel.setShowModelSheet(true) },
                onPickFolderClick = { directoryPickerLauncher.launch(null) },
                onIndexDownloadsClick = {
                    requestStoragePermissionsAndCrawl {
                        viewModel.indexDownloadsDirectory()
                        Toast.makeText(context, "Indexing Downloads folder (Main)…", Toast.LENGTH_SHORT).show()
                    }
                },
                onIndexAndroidClick = {
                    requestStoragePermissionsAndCrawl {
                        viewModel.indexAndroidDirectory()
                        Toast.makeText(context, "Indexing Android folder…", Toast.LENGTH_SHORT).show()
                    }
                },
                onIndexEntireStorageClick = {
                    requestStoragePermissionsAndCrawl {
                        viewModel.indexEntireSystemStorage()
                        Toast.makeText(context, "Scanning system storage for documents…", Toast.LENGTH_SHORT).show()
                    }
                },
                onPickDocumentClick = {
                    openDocumentLauncher.launch(
                        arrayOf(
                            "text/plain", "text/markdown", "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "text/html", "application/json", "text/csv", "image/*", "*/*"
                        )
                    )
                },
                onPickFilesClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                onLoadSampleClick = {
                    viewModel.loadSampleKnowledgeBase()
                    Toast.makeText(context, "Loading sample technical papers…", Toast.LENGTH_SHORT).show()
                },
                onLoad100SamplesClick = {
                    viewModel.load100SampleFiles()
                    Toast.makeText(context, "Indexing 100 sample research papers…", Toast.LENGTH_SHORT).show()
                },
                onSeedTestDocumentsClick = {
                    viewModel.seedTestDocuments { count, path ->
                        Toast.makeText(context, "Seeded $count documents into $path", Toast.LENGTH_LONG).show()
                    }
                },
                onScanMonitoredFolderClick = {
                    viewModel.triggerFolderMonitorScan { newFiles, newChunks, status ->
                        Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenPixelOptimizer = { showPixelOptimizerDialog = true },
                onOpenHardwareDashboard = { showHardwareDashboard = true },
                onToggleGamingMode = { viewModel.toggleGamingModePause() },
                onOpenChatBackupSheet = { showChatBackupSheet = true },
                onToggleChatBackups = { viewModel.toggleIncludeChatBackups(it) },
                onOpenUpdateSheet = { viewModel.setShowUpdateSheet(true) },
                onOpenGlossyOverlay = {
                    val overlayIntent = Intent(context, com.example.ui.overlay.GlossySearchOverlayActivity::class.java).apply {
                        action = Intent.ACTION_SEARCH
                        putExtra(com.example.widget.DocuVectorWidget.EXTRA_QUERY, query)
                    }
                    context.startActivity(overlayIntent)
                },
                onToggleTheme = onToggleTheme,
                onClearDataClick = { showClearDialog = true },
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (isMultiSelectMode) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "${selectedDocumentUris.size} selected",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Select documents to delete from database",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { viewModel.toggleMultiSelectMode(false) },
                                modifier = Modifier.testTag("btn_exit_multi_select")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Exit Multi-Select Mode",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    val allUris = results.map { it.fileUri }.distinct()
                                    viewModel.selectAllVisibleDocuments(allUris)
                                },
                                modifier = Modifier.testTag("btn_select_all")
                            ) {
                                Text(
                                    text = "Select All",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(
                                onClick = { showBatchDeleteDialog = true },
                                enabled = selectedDocumentUris.isNotEmpty(),
                                modifier = Modifier.testTag("delete_selected_documents_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Selected Documents",
                                    tint = if (selectedDocumentUris.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Transparent,
                                    modifier = Modifier.size(32.dp)
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
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "LocalDoc Finder",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (totalChunks > 0) "$totalFiles docs • $totalChunks chunks" else "Offline Vector Search",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("btn_hamburger_menu")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open Navigation Menu",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        actions = {
                            // Active Indexing indicator
                            if (indexingState is IndexingState.Progress) {
                                val prog = indexingState as IndexingState.Progress
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${prog.percent}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            // Filter & Sort Button (opens interactive Filter Sheet)
                            IconButton(
                                onClick = { showFilterBottomSheet = true },
                                modifier = Modifier.testTag("btn_top_filter_sort")
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (activeFilterCount > 0) {
                                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                Text("$activeFilterCount")
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FilterList,
                                        contentDescription = "Search Filters & Sort",
                                        tint = if (activeFilterCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Theme Toggle Button
                            IconButton(
                                onClick = onToggleTheme,
                                modifier = Modifier.testTag("theme_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = if (isDarkTheme) "Switch to Light Theme" else "Switch to Dark Theme",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Drawer / More Options button
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Open Storage & Hardware Tools",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Persistent background processing progress bar
                    if (indexingState is IndexingState.Progress) {
                        val prog = indexingState as IndexingState.Progress
                        LinearProgressIndicator(
                            progress = { prog.percent.toFloat() / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))

                        // Storage Permission Request Banner for Android 11+ PDF & Document crawling
                        if (!viewModel.hasAllFilesAccess() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            androidx.compose.material3.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Grant Storage Permission for PDF Crawling",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Android requires 'All Files Access' to automatically scan and embed PDFs in your Downloads folder and phone storage.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        androidx.compose.material3.Button(
                                            onClick = {
                                                try {
                                                    val intent = Intent(
                                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                        Uri.parse("package:" + context.packageName)
                                                    )
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {
                                                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                    context.startActivity(intent)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Grant Storage Access", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                filePickerLauncher.launch(arrayOf("application/pdf", "*/*"))
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Pick PDF Files", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Real-time Indexing Status Card displaying current documents & embedding progress
                        IndexingStatusCard(
                            totalFiles = totalFiles,
                            totalChunks = totalChunks,
                            indexingState = indexingState,
                            onStopIndexingClick = {
                                viewModel.stopIndexing()
                                Toast.makeText(context, "Indexing stopped", Toast.LENGTH_SHORT).show()
                            },
                            isIndexingActive = isIndexingActive,
                            isStoppedByUser = isIndexingStoppedByUser,
                            autoScanEnabled = fileObserverStatus.isMonitoringActive,
                            onToggleAutoScan = { viewModel.setAutoScanEnabled(it) },
                            lastScanMessage = fileObserverStatus.lastScanMessage,
                            onStartIndexingClick = {
                                // Clear the "stopped" flag first so a permission prompt that is granted later still crawls
                                viewModel.allowIndexing()
                                requestStoragePermissionsAndCrawl {
                                    viewModel.startAllIndexing()
                                    Toast.makeText(context, "Indexing started", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.padding(vertical = 4.dp),
                            speed = indexingSpeed
                        )

                        // In-App Software Update Announcement Banner
                        AppUpdateBanner(
                            updateInfo = activeAvailableUpdate,
                            onViewUpdateClick = { viewModel.setShowUpdateSheet(true) },
                            onDismissClick = {
                                activeAvailableUpdate?.let {
                                    viewModel.dismissUpdateBanner(it.versionCode)
                                }
                            }
                        )

                        // Gaming Mode Alert Banner when paused
                        AnimatedVisibility(visible = isGamingModePaused) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFF59E0B).copy(alpha = 0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            imageVector = Icons.Default.SportsEsports,
                                            contentDescription = null,
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Gaming Mode Active (Indexing Suspended)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFF59E0B)
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.toggleGamingModePause() },
                                        modifier = Modifier.testTag("btn_resume_indexing_banner")
                                    ) {
                                        Text("Resume", fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                    }
                                }
                            }
                        }

                        // Search Text Field and List Area for SQLite FTS Document Search
                        FtsDocumentSearchComponent(
                            query = query,
                            onQueryChanged = { viewModel.onQueryChanged(it) },
                            searchMode = searchMode,
                            onSearchModeChanged = { viewModel.onSearchModeChanged(it) },
                            results = results,
                            rawResults = rawResults,
                            isSearching = isSearching,
                            latencyMs = latencyMs,
                            totalFiles = totalFiles,
                            totalChunks = totalChunks,
                            selectedTag = selectedTag,
                            allTags = allTags,
                            onSelectTag = { viewModel.selectTag(it) },
                            recentSearches = recentSearches,
                            onReRunSearch = { viewModel.reRunSearchQuery(it) },
                            onDeleteHistoryItem = { viewModel.deleteSearchHistoryItem(it) },
                            onClearHistory = { viewModel.clearSearchHistory() },
                            selectedFileType = selectedFileType,
                            onSelectFileType = { viewModel.setFileTypeFilter(it) },
                            selectedConfidenceTier = selectedConfidenceTier,
                            selectedDatePreset = selectedDatePreset,
                            startDateMillis = startDateMillis,
                            endDateMillis = endDateMillis,
                            isMultiSelectMode = isMultiSelectMode,
                            selectedDocumentUris = selectedDocumentUris,
                            onToggleMultiSelectMode = { isEnable ->
                                if (isEnable != null) viewModel.toggleMultiSelectMode(isEnable)
                                else viewModel.toggleMultiSelectMode()
                            },
                            onToggleDocumentSelection = { viewModel.toggleDocumentSelection(it) },
                            onBatchDeleteRequested = { showBatchDeleteDialog = true },
                            onPreviewClick = { viewModel.onSelectResultForPreview(it) },
                            onAddTagClick = { uri, name ->
                                taggingFileUri = uri
                                taggingFileName = name
                                taggingCurrentTags = results.find { it.fileUri == uri }?.tags ?: emptyList()
                                newTagInput = ""
                            },
                            onOpenFolder = { scope.launch { drawerState.open() } },
                            onLoadSampleClick = {
                                viewModel.loadSampleKnowledgeBase()
                                Toast.makeText(context, "Loading sample knowledge base…", Toast.LENGTH_SHORT).show()
                            },
                            onIndexDownloadsClick = {
                                requestStoragePermissionsAndCrawl {
                                    viewModel.indexDownloadsDirectory()
                                    Toast.makeText(context, "Indexing Downloads folder…", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onPickFilesClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            onResetFilters = { viewModel.resetFiltersAndSort() },
                            onOpenFilterSheet = { showFilterBottomSheet = true },
                            onOpenGlossyOverlay = { res ->
                                val overlayIntent = Intent(context, com.example.ui.overlay.GlossySearchOverlayActivity::class.java).apply {
                                    action = Intent.ACTION_SEARCH
                                    putExtra(com.example.widget.DocuVectorWidget.EXTRA_QUERY, query.ifBlank { res.fileName })
                                }
                                context.startActivity(overlayIntent)
                            },
                            focusRequester = searchFocusRequester
                        )
                    }
                }

                // Background scan banner: only visible while a scan is indexing files, then it goes away
                BackgroundScanBanner(
                    status = fileObserverStatus,
                    onStop = {
                        viewModel.stopIndexing()
                        Toast.makeText(context, "Indexing stopped", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    // Read-only, expandable document detail sheet (full content + metadata) opened from any search result
    selectedPreview?.let { preview ->
        DocumentDetailSheet(
            result = preview,
            repository = viewModel.repository,
            searchQuery = query,
            onDismiss = { viewModel.onSelectResultForPreview(null) }
        )
    }

    // Dedicated Search & Filter Modal Bottom Sheet
    if (showFilterBottomSheet) {
        SearchFilterBottomSheet(
            searchMode = searchMode,
            onSearchModeChanged = { viewModel.onSearchModeChanged(it) },
            selectedTag = selectedTag,
            allTags = allTags,
            onSelectTag = { viewModel.selectTag(it) },
            selectedFileType = selectedFileType,
            availableFileTypes = availableFileTypes,
            fileTypeCounts = fileTypeCounts,
            totalResultsCount = rawResults.size,
            onSelectFileType = { viewModel.setFileTypeFilter(it) },
            selectedConfidenceTier = selectedConfidenceTier,
            onSelectConfidenceTier = { viewModel.setConfidenceTierFilter(it) },
            confidenceDistribution = confidenceDistribution,
            rawResults = rawResults,
            isDarkTheme = isDarkTheme,
            onResultClick = { result ->
                showFilterBottomSheet = false
                viewModel.onSelectResultForPreview(result)
            },
            currentSortOrder = sortOrder,
            onSelectSortOrder = { viewModel.setSortOrder(it) },
            selectedDatePreset = selectedDatePreset,
            startDateMillis = startDateMillis,
            endDateMillis = endDateMillis,
            vectorWeight = vectorWeight,
            bm25Weight = bm25Weight,
            onSetHybridWeights = { vW, bW -> viewModel.setHybridWeights(vW, bW) },
            onSelectDatePreset = { viewModel.setDatePreset(it) },
            onSelectDateRange = { start, end -> viewModel.setDateRange(start, end) },
            onClearDateFilter = { viewModel.clearDateFilter() },
            onResetFilters = { viewModel.resetFiltersAndSort() },
            onDismiss = { showFilterBottomSheet = false }
        )
    }

    // Confirmation Dialog for Clearing All Records
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(text = "Clear Document Index?") },
            text = { Text(text = "This will remove all indexed chunks and full-text virtual tables from local device storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllData()
                        showClearDialog = false
                    }
                ) {
                    Text(text = "Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    // Confirmation Dialog for Batch Deleting Multiple Selected Documents
    if (showBatchDeleteDialog) {
        val count = selectedDocumentUris.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text(text = "Delete $count Document(s)?") },
            text = {
                Text(
                    text = "Are you sure you want to delete the $count selected document(s) from the local database? All vector embeddings and full-text search entries will be permanently removed."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedDocuments { deletedCount ->
                            Toast.makeText(context, "Deleted $deletedCount document(s) from local database", Toast.LENGTH_SHORT).show()
                        }
                        showBatchDeleteDialog = false
                    },
                    modifier = Modifier.testTag("confirm_batch_delete_dialog_button")
                ) {
                    Text(text = "Delete ($count)", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBatchDeleteDialog = false },
                    modifier = Modifier.testTag("cancel_batch_delete_dialog_button")
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    // Hardware Acceleration & Pixel Tensor Optimizer Dialog
    if (showPixelOptimizerDialog) {
        PixelOptimizationDialog(
            profile = pixelProfile,
            onBenchmarkRequested = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val testQueries = listOf(
                        "Google Tensor EdgeTPU hardware neural inference",
                        "Heterogeneous multi-core CPU cluster thread affinity",
                        "Systolic array matrix multiplication INT8 quantization",
                        "Dynamic thermal throttling and adaptive batch pacing",
                        "High performance vector embeddings and cosine similarity"
                    )
                    val start = System.currentTimeMillis()
                    for (q in testQueries) {
                        viewModel.repository.embeddingEngine.embedText(q)
                    }
                    val elapsed = System.currentTimeMillis() - start
                    (elapsed / testQueries.size).coerceAtLeast(1)
                }
            },
            onDismiss = { showPixelOptimizerDialog = false }
        )
    }

    // Full Hardware & Gaming Mode Dashboard Modal Sheet
    if (showHardwareDashboard) {
        val indexerDesc = when {
            isGamingModePaused -> "PAUSED (Gaming Mode Active)"
            indexingState is IndexingState.Progress -> "Active (${(indexingState as IndexingState.Progress).percent}%)"
            chatIndexingProgress is com.example.service.ChatIndexingProgress.Active -> "Indexing Chat Backup"
            else -> "Idle (Ready)"
        }
        HardwareDashboardSheet(
            metrics = hardwareMetrics,
            indexerStatusText = indexerDesc,
            isIndexingActive = indexingState is IndexingState.Progress || chatIndexingProgress is com.example.service.ChatIndexingProgress.Active,
            onTogglePause = { viewModel.toggleGamingModePause() },
            onDismiss = { showHardwareDashboard = false }
        )
    }

    // SMS & Chat Backup Indexing Modal Sheet
    if (showChatBackupSheet) {
        ChatBackupSheet(
            includeChatBackups = includeChatBackups,
            onToggleIncludeChatBackups = { viewModel.toggleIncludeChatBackups(it) },
            indexingProgress = chatIndexingProgress,
            isGamingPaused = isGamingModePaused,
            onToggleGamingPause = { viewModel.toggleGamingModePause() },
            onImportBackupUri = { uri -> viewModel.importChatBackupUri(uri) },
            onSeedSampleChatBackup = {
                viewModel.seedSampleChatBackups()
                Toast.makeText(context, "Indexing sample SMS & WhatsApp chat backups…", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showChatBackupSheet = false }
        )
    }

    // Indexing & Embedding Models Sheet
    if (showModelSheet) {
        val modelSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        com.example.ui.components.EmbeddingModelSheet(
            activeModel = activeEmbeddingModel,
            isGeminiConfigured = isGeminiConfigured,
            isReindexing = isReindexingModel,
            reindexingProgress = reindexingModelProgress,
            reindexingStatus = reindexingModelStatus,
            onSelectModel = { selected ->
                viewModel.selectEmbeddingModel(selected)
            },
            onReindexClick = {
                viewModel.reindexKnowledgeBaseWithActiveModel { count ->
                    Toast.makeText(context, "Re-indexed $count chunks with ${activeEmbeddingModel.shortName}", Toast.LENGTH_LONG).show()
                }
            },
            onTestBenchmark = { q, textA, textB ->
                viewModel.benchmarkSemanticSimilarity(q, textA, textB)
            },
            onDismiss = { viewModel.setShowModelSheet(false) },
            sheetState = modelSheetState
        )
    }

    // Search Mode Info Dialog
    if (showSearchModeInfoDialog) {
        AlertDialog(
            onDismissRequest = { showSearchModeInfoDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(text = "Search Query Modes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column {
                        Text(
                            text = "Hybrid (RRF) • Recommended",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Combines on-device neural vector embeddings with SQLite FTS4 BM25 keyword ranking via Reciprocal Rank Fusion. Delivers both semantic comprehension and exact word precision.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column {
                        Text(
                            text = "Vector (KNN)",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Matches concepts and meanings using dense cosine similarity, finding relevant answers even when the query uses completely different vocabulary.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column {
                        Text(
                            text = "Keyword (FTS)",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "High-speed lexical search via SQLite full-text virtual tables. Best for finding exact part numbers, code syntax, and proper nouns.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchModeInfoDialog = false }) {
                    Text("Got It")
                }
            }
        )
    }

    // Document Categorization & Tagging Dialog
    taggingFileUri?.let { fileUri ->
        val fileName = taggingFileName ?: "Document"
        val popularCategories = listOf("AI & ML", "Distributed", "Quantum", "Bio & Health", "Storage & DB", "Research", "Work", "Personal")

        AlertDialog(
            onDismissRequest = { taggingFileUri = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Label,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Document Categories", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Manage tags for \"$fileName\":",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (taggingCurrentTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Active tags (tap × to remove):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(taggingCurrentTags) { tag ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.clickable {
                                        viewModel.removeTagFromDocument(fileUri, tag)
                                        taggingCurrentTags = taggingCurrentTags.filter { it != tag }
                                        Toast.makeText(context, "Removed tag #$tag", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "#$tag",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove tag",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newTagInput,
                        onValueChange = { newTagInput = it },
                        label = { Text("Add new tag") },
                        placeholder = { Text("e.g. ProjectX, Research") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tag_input_field")
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Quick categories:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val availableSuggestions = (popularCategories + allTags).distinct()
                        .filter { !taggingCurrentTags.contains(it) }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(availableSuggestions) { tag ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { newTagInput = tag }
                            ) {
                                Text(
                                    text = "+ #$tag",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val tagToAdd = newTagInput.trim()
                        if (tagToAdd.isNotBlank()) {
                            viewModel.addTagToDocument(fileUri, tagToAdd)
                            Toast.makeText(context, "Added tag #$tagToAdd to $fileName", Toast.LENGTH_SHORT).show()
                        }
                        taggingFileUri = null
                    },
                    modifier = Modifier.testTag("confirm_add_tag_button")
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { taggingFileUri = null }) {
                    Text("Close")
                }
            }
        )
    }

    // In-App Software Update Modal Bottom Sheet
    if (showUpdateSheet) {
        AppUpdateSheet(
            updateCheckResult = updateCheckResult,
            downloadState = downloadState,
            updateConfig = updateConfig,
            isCheckingForUpdates = isCheckingForUpdates,
            onCheckForUpdates = { viewModel.checkForUpdates(forceCheck = true) },
            onStartDownload = { info -> viewModel.startDownloadUpdate(info) },
            onCancelDownload = { viewModel.cancelDownloadUpdate() },
            onInstallApk = { file -> viewModel.installDownloadedApk(file) },
            onUpdateConfig = { config -> viewModel.updateConfig(config) },
            onSimulateUpdate = { viewModel.simulateUpdate() },
            onDismiss = { viewModel.setShowUpdateSheet(false) }
        )
    }
}

@Composable
private fun InitialQueryPrompt(
    totalChunks: Int,
    onSuggestionClick: (String) -> Unit,
    onOpenDrawer: () -> Unit
) {
    val suggestions = listOf(
        "LiteRT embedded architecture",
        "Raft distributed consensus",
        "Qubit superposition and Shor",
        "Storage Access Framework SAF",
        "Dense vector embeddings",
        "mRNA lipid nanoparticles"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FindInPage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (totalChunks > 0) "Instant On-Device Search" else "Index Documents First",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (totalChunks > 0)
                "Type anything in the search bar above. Use the ☰ menu for folder indexing & hardware tools, or tap 'Filter & Sort' to customize results."
            else
                "Open the ☰ Hamburger Menu to index folders, load sample papers, or manage hardware acceleration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        if (totalChunks > 0) {
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Try these sample queries:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                suggestions.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { item ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onSuggestionClick(item) }
                            ) {
                                Text(
                                    text = item,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoResultsPrompt(
    query: String,
    searchMode: SearchMode,
    onSwitchToHybrid: () -> Unit,
    onClearFilters: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No matches found for \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (searchMode != SearchMode.HYBRID)
                "Try switching to Hybrid mode to combine both dense vector semantics and full-text keyword matching."
            else
                "Try phrasing with different keywords, adjusting confidence filters, or clearing active file type tags.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (searchMode != SearchMode.HYBRID) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSwitchToHybrid() }
                ) {
                    Text(
                        text = "Switch to Hybrid",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onClearFilters() }
            ) {
                Text(
                    text = "Clear Filters",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FilteredEmptyPrompt(
    selectedFileType: String?,
    selectedTier: String?,
    selectedDatePreset: DateRangePreset,
    startDateMillis: Long?,
    endDateMillis: Long?,
    totalCount: Int,
    onClearFilter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Filtered Out All Results",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))

        val activeFiltersDesc = buildString {
            if (selectedFileType != null) append(".${selectedFileType.lowercase()} ")
            if (selectedTier != null) append("$selectedTier confidence ")
            if (selectedDatePreset != DateRangePreset.ALL_TIME || startDateMillis != null) {
                append("(${formatDateRangeLabel(startDateMillis, endDateMillis, selectedDatePreset)})")
            }
        }

        Text(
            text = "Your search matched $totalCount document(s), but none match the active filters: $activeFiltersDesc.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        FilledTonalButton(
            onClick = onClearFilter,
            modifier = Modifier.testTag("btn_clear_filter_empty_state")
        ) {
            Text("Reset All Filters")
        }
    }
}
