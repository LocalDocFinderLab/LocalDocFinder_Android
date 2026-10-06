package com.example.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.repository.DocumentRepository
import com.example.engine.ConfidenceDistribution
import com.example.engine.DateRangePreset
import com.example.engine.ExecutionBackend
import com.example.engine.PixelTensorOptimizer
import com.example.engine.SearchFilterState
import com.example.engine.SearchMode
import com.example.engine.SearchResult
import com.example.engine.SearchSortOrder
import com.example.engine.calculateDateRangeBounds
import com.example.updater.engine.AppUpdateChecker
import com.example.updater.engine.AppUpdateDownloader
import com.example.updater.engine.AppUpdateInstaller
import com.example.updater.model.AppUpdateInfo
import com.example.updater.model.DownloadState
import com.example.updater.model.UpdateCheckResult
import com.example.updater.model.UpdateConfig
import com.example.updater.repository.UpdatePreferences
import com.example.worker.DocumentIndexWorker
import com.example.worker.IndexingController
import com.example.worker.PdfSyncWorker
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface IndexingState {
    data object Idle : IndexingState
    data class Progress(
        val current: Int,
        val total: Int,
        val percent: Int,
        val currentFile: String,
        val currentPhase: String
    ) : IndexingState
    data class Completed(
        val chunksCount: Int,
        val message: String,
        val failedCount: Int = 0,
        val errorSummary: String? = null
    ) : IndexingState
    data class Error(val message: String) : IndexingState
}

data class FileObserverProgressState(
    val isRunning: Boolean = false,
    val isMonitoringActive: Boolean = true,
    val activeTaskName: String = "Background scan",
    val currentFile: String? = null,
    val currentPhase: String = "Checking Downloads & folders for new files",
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val percent: Int = 0,
    val lastScanMessage: String = "Auto-scan checks for new files every 15 minutes.",
    val lastScanTimeMillis: Long = 0L,
    val newlyIndexedFiles: Int = 0,
    val newlyIndexedChunks: Int = 0
)

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repository = DocumentRepository(application)
    private val workManager = WorkManager.getInstance(application)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchMode = MutableStateFlow(SearchMode.HYBRID)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    private val _rawSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val rawSearchResults: StateFlow<List<SearchResult>> = _rawSearchResults.asStateFlow()

    private val _selectedFileType = MutableStateFlow<String?>(null)
    val selectedFileType: StateFlow<String?> = _selectedFileType.asStateFlow()

    private val _sortOrder = MutableStateFlow(SearchSortOrder.RELEVANCE)
    val sortOrder: StateFlow<SearchSortOrder> = _sortOrder.asStateFlow()

    // Configurable hybrid search ranking weights (Vector similarity vs SQLite FTS BM25)
    private val _vectorWeight = MutableStateFlow(0.5f)
    val vectorWeight: StateFlow<Float> = _vectorWeight.asStateFlow()

    private val _bm25Weight = MutableStateFlow(0.5f)
    val bm25Weight: StateFlow<Float> = _bm25Weight.asStateFlow()

    fun setHybridWeights(vecW: Float, bm25W: Float) {
        _vectorWeight.value = vecW.coerceIn(0.0f, 1.0f)
        _bm25Weight.value = bm25W.coerceIn(0.0f, 1.0f)
    }

    /**
     * Ranking algorithm combining SQLite FTS BM25 relevance scores with vector similarity scores (cosine similarity).
     */
    fun calculateHybridRanking(
        rawResults: List<SearchResult>,
        vectorW: Float = _vectorWeight.value,
        bm25W: Float = _bm25Weight.value,
        mode: SearchMode = _searchMode.value
    ): List<SearchResult> {
        if (rawResults.isEmpty()) return emptyList()

        val totalW = vectorW + bm25W
        val normVecW = if (totalW > 0f) vectorW / totalW else 0.5f
        val normBm25W = if (totalW > 0f) bm25W / totalW else 0.5f

        return rawResults.map { res ->
            val vecScore = res.cosineSimilarity.coerceIn(0f, 1f)
            val bm25Score = res.bm25Score.coerceIn(0f, 1f)

            val combined = when (mode) {
                SearchMode.HYBRID -> (normVecW * vecScore + normBm25W * bm25Score).coerceIn(0f, 1f)
                SearchMode.VECTOR -> vecScore
                // FTS candidates that were semantically re-scored (cosine > 0) rank by meaning;
                // unscored candidates (e.g. embedding unavailable) keep their BM25 order.
                SearchMode.KEYWORD -> if (res.cosineSimilarity > 0f) vecScore else bm25Score
            }

            res.copy(
                bm25Score = bm25Score,
                combinedScore = combined
            )
        }
    }

    /**
     * Ranks SQLite FTS full-text candidates by semantic relevance.
     *
     * For every candidate chunk the cosine similarity between the query embedding and the chunk's stored
     * TFLite INT8 quantized embedding BLOB is computed directly in the quantized domain
     * (see [com.example.engine.VectorSimilarityUtils.cosineSimilarityWithEmbeddingBlob]), so no per-chunk
     * float array is allocated. Results are returned best-first: highest cosine similarity, ties broken by BM25.
     *
     * @param ftsResults FTS/keyword candidates (already filtered by the SQLite full-text query)
     * @param queryEmbedding embedding of the user's query from the active model
     * @param embeddingBlobs chunkId -> stored embedding BLOB for the candidates
     * @param fallbackQueryEmbedding query embedding from the on-device engine, used for chunks that were
     * indexed with a model whose dimension differs from [queryEmbedding]
     */
    fun rankBySemanticRelevance(
        ftsResults: List<SearchResult>,
        queryEmbedding: FloatArray,
        embeddingBlobs: Map<Long, ByteArray>,
        fallbackQueryEmbedding: FloatArray? = null
    ): List<SearchResult> {
        if (ftsResults.isEmpty() || queryEmbedding.isEmpty()) return ftsResults

        return ftsResults
            .map { res ->
                val blob = embeddingBlobs[res.chunkId]
                val queryVec = when {
                    blob == null || blob.isEmpty() -> null
                    else -> {
                        val dim = com.example.engine.TensorFlowLiteQuantizer.blobDimension(blob)
                        when {
                            dim == queryEmbedding.size -> queryEmbedding
                            fallbackQueryEmbedding != null && dim == fallbackQueryEmbedding.size -> fallbackQueryEmbedding
                            else -> null
                        }
                    }
                }
                val cosine = if (blob != null && queryVec != null) {
                    com.example.engine.VectorSimilarityUtils
                        .cosineSimilarityWithEmbeddingBlob(queryVec, blob)
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                res.copy(cosineSimilarity = cosine, combinedScore = if (cosine > 0f) cosine else res.bm25Score)
            }
            .sortedWith(
                compareByDescending<SearchResult> { it.combinedScore }
                    .thenByDescending { it.bm25Score }
            )
    }

    /**
     * Loads the quantized embeddings of the given FTS candidates and ranks them with [rankBySemanticRelevance].
     * Falls back to the unchanged (BM25-ordered) list if embedding the query fails.
     */
    private suspend fun semanticallyRankFtsResults(query: String, ftsResults: List<SearchResult>): List<SearchResult> {
        if (ftsResults.isEmpty()) return ftsResults
        return try {
            val queryEmbedding = repository.embedQuery(query)
            val blobs = repository.loadEmbeddingBlobs(ftsResults.map { it.chunkId })
            val needsFallback = blobs.values.any { blob ->
                val dim = com.example.engine.TensorFlowLiteQuantizer.blobDimension(blob)
                dim > 0 && dim != queryEmbedding.size
            }
            val fallback = if (needsFallback) repository.embeddingEngine.embedText(query) else null
            rankBySemanticRelevance(ftsResults, queryEmbedding, blobs, fallback)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ftsResults
        }
    }

    private val _selectedConfidenceTier = MutableStateFlow<String?>(null)
    val selectedConfidenceTier: StateFlow<String?> = _selectedConfidenceTier.asStateFlow()

    private val _startDateMillis = MutableStateFlow<Long?>(null)
    val startDateMillis: StateFlow<Long?> = _startDateMillis.asStateFlow()

    private val _endDateMillis = MutableStateFlow<Long?>(null)
    val endDateMillis: StateFlow<Long?> = _endDateMillis.asStateFlow()

    private val _datePreset = MutableStateFlow(DateRangePreset.ALL_TIME)
    val datePreset: StateFlow<DateRangePreset> = _datePreset.asStateFlow()

    val availableFileTypes: StateFlow<List<String>> = _rawSearchResults.map { list ->
        list.map { it.fileExtension }.filter { it.isNotBlank() }.distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fileTypeCounts: StateFlow<Map<String, Int>> = _rawSearchResults.map { list ->
        list.groupingBy { it.fileExtension }.eachCount()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val confidenceDistribution: StateFlow<ConfidenceDistribution> = _rawSearchResults.map { list ->
        ConfidenceDistribution.fromResults(list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConfidenceDistribution.EMPTY)

    val filterState: StateFlow<SearchFilterState> = combine(
        _selectedFileType,
        _sortOrder,
        _startDateMillis,
        _endDateMillis
    ) { fileType, sort, startMillis, endMillis ->
        SearchFilterState(
            selectedFileType = fileType,
            sortOrder = sort,
            startDateMillis = startMillis,
            endDateMillis = endMillis
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchFilterState())

    private data class RankingConfigState(
        val vectorWeight: Float = 0.5f,
        val bm25Weight: Float = 0.5f,
        val mode: SearchMode = SearchMode.HYBRID
    )

    private val rankingConfigState: StateFlow<RankingConfigState> = combine(
        _vectorWeight,
        _bm25Weight,
        _searchMode
    ) { vW, bW, mode ->
        RankingConfigState(vW, bW, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RankingConfigState())

    val searchResults: StateFlow<List<SearchResult>> = combine(
        _rawSearchResults,
        filterState,
        _selectedConfidenceTier,
        rankingConfigState
    ) { rawList, filter, tier, config ->
        val ranked = calculateHybridRanking(rawList, config.vectorWeight, config.bm25Weight, config.mode)
        var filtered = filter.apply(ranked)
        if (tier != null) {
            filtered = when (tier) {
                "HIGH" -> filtered.filter { it.combinedScore >= 0.70f || it.cosineSimilarity >= 0.70f }
                "MODERATE" -> filtered.filter { (it.combinedScore in 0.40f..<0.70f) || (it.cosineSimilarity in 0.40f..<0.70f) }
                "LOW" -> filtered.filter { it.combinedScore < 0.40f && it.cosineSimilarity < 0.40f }
                else -> filtered
            }
        }
        filtered
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedResultForPreview = MutableStateFlow<SearchResult?>(null)
    val selectedResultForPreview: StateFlow<SearchResult?> = _selectedResultForPreview.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchLatencyMs = MutableStateFlow<Long>(0L)
    val searchLatencyMs: StateFlow<Long> = _searchLatencyMs.asStateFlow()

    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()

    private val _currentTreeUri = MutableStateFlow<Uri?>(null)
    val currentTreeUri: StateFlow<Uri?> = _currentTreeUri.asStateFlow()

    private val _executionBackend = MutableStateFlow(repository.getBackend())
    val executionBackend: StateFlow<ExecutionBackend> = _executionBackend.asStateFlow()

    // Model Management State
    val activeEmbeddingModel: StateFlow<com.example.engine.model.EmbeddingModelType> = repository.modelManager.activeModel

    /** The model really in use: the active one, or the built-in embedder when its files aren't installed. */
    val effectiveEmbeddingModel: StateFlow<com.example.engine.model.EmbeddingModelType> = repository.modelManager.effectiveModel

    val installedEmbeddingModels: StateFlow<Set<com.example.engine.model.EmbeddingModelType>> = repository.modelManager.installedModels

    /** Chunks indexed with a different model than the one in use; they need a re-index for semantic search. */
    val staleChunkCount: StateFlow<Int> = repository.staleChunkCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _modelImportStatus = MutableStateFlow<String?>(null)
    val modelImportStatus: StateFlow<String?> = _modelImportStatus.asStateFlow()

    fun importEmbeddingModel(model: com.example.engine.model.EmbeddingModelType, tflite: Uri, vocab: Uri) {
        _modelImportStatus.value = "Importing ${model.shortName}…"
        viewModelScope.launch {
            val error = repository.importEmbeddingModel(model, tflite, vocab)
            _modelImportStatus.value = error ?: "${model.shortName} installed. Re-index to use it for your existing documents."
        }
    }

    fun removeEmbeddingModel(model: com.example.engine.model.EmbeddingModelType) {
        viewModelScope.launch {
            repository.removeEmbeddingModel(model)
            _modelImportStatus.value = "${model.shortName} removed."
        }
    }

    fun reportModelImportProblem(message: String) {
        _modelImportStatus.value = message
    }

    private val _isReindexingModel = MutableStateFlow(false)
    val isReindexingModel: StateFlow<Boolean> = _isReindexingModel.asStateFlow()

    private val _reindexingModelProgress = MutableStateFlow(0f)
    val reindexingModelProgress: StateFlow<Float> = _reindexingModelProgress.asStateFlow()

    private val _reindexingModelStatus = MutableStateFlow("")
    val reindexingModelStatus: StateFlow<String> = _reindexingModelStatus.asStateFlow()

    private val _showModelSheet = MutableStateFlow(false)
    val showModelSheet: StateFlow<Boolean> = _showModelSheet.asStateFlow()

    fun setShowModelSheet(show: Boolean) {
        _showModelSheet.value = show
    }

    fun selectEmbeddingModel(modelType: com.example.engine.model.EmbeddingModelType) {
        repository.modelManager.setActiveModel(modelType)
        // Refresh search if query is active
        if (_query.value.isNotBlank() || _selectedTag.value != null) {
            performSearch(_query.value, _searchMode.value, _selectedTag.value)
        }
    }

    fun reindexKnowledgeBaseWithActiveModel(onComplete: ((Int) -> Unit)? = null) {
        if (_isReindexingModel.value) return
        _isReindexingModel.value = true
        _reindexingModelProgress.value = 0.05f
        _reindexingModelStatus.value = "Starting re-indexing with ${effectiveEmbeddingModel.value.shortName}…"

        reindexJob = viewModelScope.launch {
            try {
                val updatedCount = repository.reindexAllDocumentsWithActiveModel { cur, total, file ->
                    val pct = if (total > 0) cur.toFloat() / total.toFloat() else 0.5f
                    _reindexingModelProgress.value = pct
                    _reindexingModelStatus.value = "Re-embedding $file ($cur/$total) with ${effectiveEmbeddingModel.value.shortName}…"
                }
                _reindexingModelProgress.value = 1.0f
                _reindexingModelStatus.value = "Successfully re-indexed $updatedCount chunks using ${effectiveEmbeddingModel.value.shortName}!"
                delay(1200)
                performSearch(_query.value, _searchMode.value, _selectedTag.value)
                onComplete?.invoke(updatedCount)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _reindexingModelStatus.value = "Re-indexing stopped"
                throw e
            } catch (e: Exception) {
                _reindexingModelStatus.value = "Re-indexing error: ${e.message}"
            } finally {
                _isReindexingModel.value = false
            }
        }
    }

    suspend fun benchmarkSemanticSimilarity(
        query: String,
        textA: String,
        textB: String
    ): Triple<Float, Float, Long> {
        return repository.modelManager.benchmarkSemanticSimilarity(query, textA, textB)
    }

    val pixelProfile: StateFlow<PixelTensorOptimizer.PixelProfile> = repository.pixelOptimizer.currentProfile

    val totalChunksCount: StateFlow<Int> = repository.totalChunksCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalFilesCount: StateFlow<Int> = repository.totalFilesCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val allTags: StateFlow<List<String>> = repository.allTags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentSearches: StateFlow<List<com.example.data.local.SearchHistoryEntity>> = repository.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _includeChatBackups = MutableStateFlow(false)
    val includeChatBackups: StateFlow<Boolean> = _includeChatBackups.asStateFlow()

    val hardwareMetrics: StateFlow<com.example.engine.HardwareMetrics> = com.example.engine.HardwareMonitor.metrics
    val isGamingModePaused: StateFlow<Boolean> = com.example.engine.HardwareMonitor.isIndexingPaused
    val indexingSpeed: StateFlow<com.example.engine.IndexingPowerPolicy.Speed> = com.example.engine.IndexingPowerPolicy.speed
    val fullSpeedEnabled: StateFlow<Boolean> = com.example.engine.IndexingPowerPolicy.forceFullSpeed

    /** Manual "Full speed" for big first-time indexing runs; remembered until switched off. */
    fun setFullSpeed(enabled: Boolean) {
        com.example.engine.IndexingPowerPolicy.setForceFullSpeed(getApplication(), enabled)
    }
    val chatIndexingProgress: StateFlow<com.example.service.ChatIndexingProgress> = com.example.service.ChatBackupIndexingService.serviceProgress

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _isDarkTheme = MutableStateFlow<Boolean?>(null)
    val isDarkTheme: StateFlow<Boolean?> = _isDarkTheme.asStateFlow()

    // Multi-select state
    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    private val _selectedDocumentUris = MutableStateFlow<Set<String>>(emptySet())
    val selectedDocumentUris: StateFlow<Set<String>> = _selectedDocumentUris.asStateFlow()

    // Folder Monitor & FileObserver status
    private val _fileObserverStatus = MutableStateFlow(FileObserverProgressState())
    val fileObserverStatus: StateFlow<FileObserverProgressState> = _fileObserverStatus.asStateFlow()

    private val _folderMonitorStatus = MutableStateFlow(
        com.example.worker.FolderMonitorWorker.getLastScanInfo(application).third
    )
    val folderMonitorStatus: StateFlow<String> = _folderMonitorStatus.asStateFlow()

    // --- Start / Stop indexing ---
    /** True after the user pressed Stop; background scans stay off until indexing is started again. */
    val isIndexingStoppedByUser: StateFlow<Boolean> = IndexingController.stoppedByUserFlow(application)

    // --- Crash Loop & Safe Mode Protection ---
    val quarantinedCount: StateFlow<Int> = com.example.engine.FailedDocumentRegistry.quarantinedCount
    val safeModeActive: StateFlow<Boolean> = com.example.engine.FailedDocumentRegistry.safeModeActive
    val crashAvertedNotice: StateFlow<String?> = com.example.engine.FailedDocumentRegistry.crashAvertedNotice

    fun getQuarantinedDocuments(): List<com.example.engine.QuarantinedDocument> =
        com.example.engine.FailedDocumentRegistry.getQuarantinedList(getApplication())

    fun dismissSafeMode() {
        com.example.engine.FailedDocumentRegistry.dismissSafeMode(getApplication())
    }

    fun clearQuarantine() {
        com.example.engine.FailedDocumentRegistry.clearAll(getApplication())
    }

    fun retryQuarantinedDocument(fileUri: String) {
        com.example.engine.FailedDocumentRegistry.unquarantineFile(getApplication(), fileUri)
    }

    private val _isDocumentIndexRunning = MutableStateFlow(false)

    /** True while any indexing work is running: manual index jobs, background scans, chat import or model re-index. */
    val isIndexingActive: StateFlow<Boolean> = combine(
        _indexingState,
        _isDocumentIndexRunning,
        _fileObserverStatus,
        chatIndexingProgress,
        _isReindexingModel
    ) { state, docIndexRunning, scan, chat, reindexing ->
        state is IndexingState.Progress ||
                docIndexRunning ||
                scan.isRunning ||
                chat is com.example.service.ChatIndexingProgress.Active ||
                reindexing
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var reindexJob: Job? = null

    val designatedMonitoredFolder: java.io.File
        get() = com.example.worker.FolderMonitorWorker.getDesignatedFolder(getApplication())

    // --- In-App Self-Update / OTA System ---
    private val updatePreferences = UpdatePreferences(application)
    private val updateChecker = AppUpdateChecker(application)
    private val updateDownloader = AppUpdateDownloader(application)

    val updateConfig: StateFlow<UpdateConfig> = updatePreferences.config

    private val _updateCheckResult = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Idle)
    val updateCheckResult: StateFlow<UpdateCheckResult> = _updateCheckResult.asStateFlow()

    val downloadState: StateFlow<DownloadState> = updateDownloader.downloadState

    private val _isCheckingForUpdates = MutableStateFlow(false)
    val isCheckingForUpdates: StateFlow<Boolean> = _isCheckingForUpdates.asStateFlow()

    private val _showUpdateSheet = MutableStateFlow(false)
    val showUpdateSheet: StateFlow<Boolean> = _showUpdateSheet.asStateFlow()

    private val _dismissedUpdateCode = MutableStateFlow(updatePreferences.config.value.dismissedVersionCode)
    val dismissedUpdateCode: StateFlow<Int> = _dismissedUpdateCode.asStateFlow()

    val activeAvailableUpdate: StateFlow<AppUpdateInfo?> = combine(
        _updateCheckResult,
        _dismissedUpdateCode
    ) { result, dismissedCode ->
        if (result is UpdateCheckResult.UpdateAvailable && result.info.versionCode > dismissedCode) {
            result.info
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun toggleTheme(currentDark: Boolean) {
        _isDarkTheme.value = !currentDark
    }

    private var searchJob: Job? = null
    private var fullCrawlRequested = false
    private var downloadsCrawlRequested = false

    init {
        com.example.engine.HardwareMonitor.startMonitoring(application, viewModelScope)

        // Auto-check for updates on launch if enabled
        viewModelScope.launch {
            delay(1500) // gentle delay to allow initial UI compose
            if (updatePreferences.config.value.autoCheckEnabled) {
                checkForUpdates(forceCheck = false)
            }
        }

        // Debounced search query observer (150ms debounce)
        viewModelScope.launch {
            _query
                .debounce(150)
                .distinctUntilChanged()
                .collect { q ->
                    performSearch(q, _searchMode.value, _selectedTag.value)
                }
        }

        // Auto-crawl device Downloads & storage for real PDFs on launch
        viewModelScope.launch {
            delay(300)
            // The user pressed Stop earlier: do not start anything on their behalf.
            if (IndexingController.isStoppedByUser(application)) return@launch
            // Safe Mode is active (recent crash or quarantine): do not auto-crawl to prevent crash loops
            if (com.example.engine.FailedDocumentRegistry.isSafeModeActive(application)) return@launch

            val directUris = repository.getIndexedFileUrisDirect()
            if (directUris.isEmpty()) {
                // First launch: index the user's Downloads (or the whole storage when permitted)
                autoCrawlOnPermissionGranted()
            } else {
                // Later launches: one quiet check for new or changed files. It never shows a progress card;
                // the periodic scan (every 15 min) is already scheduled by DocuVectorApp.
                delay(500)
                com.example.worker.FolderMonitorWorker.triggerImmediateScan(application)
                // The whole-storage crawl runs once, as soon as "All files access" is granted.
                autoCrawlOnPermissionGranted()
            }
        }

        // Keep document list refreshed when total files count changes and query is empty
        viewModelScope.launch {
            repository.totalFilesCount.collect {
                if (_query.value.isBlank() && _selectedTag.value == null) {
                    loadAllIndexedDocuments()
                }
            }
        }
        // Reflect manual index jobs in the Start/Stop button even if they were started before this screen opened
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(DocumentIndexWorker.TAG).collect { infos ->
                _isDocumentIndexRunning.value = infos.any { it.state == WorkInfo.State.RUNNING }
            }
        }

        // Track FileObserver, FolderMonitor, & PdfSync background tasks progress in real-time
        viewModelScope.launch {
            combine(
                workManager.getWorkInfosByTagFlow(com.example.worker.DownloadsFileObserverWorker.TAG),
                workManager.getWorkInfosByTagFlow(com.example.worker.FolderMonitorWorker.TAG),
                workManager.getWorkInfosByTagFlow(com.example.worker.PdfSyncWorker.TAG)
            ) { downloadsList, folderList, pdfSyncList ->
                val runningDownloads = downloadsList.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val runningFolder = folderList.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val runningPdfSync = pdfSyncList.firstOrNull { it.state == WorkInfo.State.RUNNING }

                val activeWork = runningDownloads ?: runningFolder ?: runningPdfSync
                if (activeWork != null) {
                    val progress = activeWork.progress
                    val curFile = progress.getString("current_file") ?: "Scanning storage…"
                    val phase = progress.getString("phase") ?: "FileObserver background indexer running"
                    val processed = progress.getInt("processed_count", 0)
                    val total = progress.getInt("total_count", 0)
                    val pct = progress.getInt("percent", if (total > 0) ((processed.toFloat() / total.toFloat()) * 100).toInt() else 0)
                    val isDownloads = runningDownloads != null
                    val isPdfSync = runningPdfSync != null

                    FileObserverProgressState(
                        isRunning = true,
                        isMonitoringActive = com.example.worker.FolderMonitorWorker.isMonitoringEnabled(getApplication()),
                        activeTaskName = when {
                            isDownloads -> "Downloads scan"
                            isPdfSync -> "PDF sync"
                            else -> "Folder scan"
                        },
                        currentFile = curFile,
                        currentPhase = phase,
                        processedCount = processed,
                        totalCount = total,
                        percent = pct,
                        lastScanMessage = _fileObserverStatus.value.lastScanMessage,
                        lastScanTimeMillis = System.currentTimeMillis(),
                        newlyIndexedFiles = _fileObserverStatus.value.newlyIndexedFiles,
                        newlyIndexedChunks = _fileObserverStatus.value.newlyIndexedChunks
                    )
                } else {
                    val latestDone = (downloadsList + folderList + pdfSyncList)
                        .filter { it.state == WorkInfo.State.SUCCEEDED }
                        .maxByOrNull { it.id.hashCode() }

                    val outData = latestDone?.outputData
                    val msg = outData?.getString(com.example.worker.DownloadsFileObserverWorker.KEY_OBSERVER_MESSAGE)
                        ?: outData?.getString(com.example.worker.FolderMonitorWorker.KEY_SCAN_MESSAGE)
                        ?: outData?.getString(com.example.worker.PdfSyncWorker.KEY_SYNC_MESSAGE)
                        ?: _fileObserverStatus.value.lastScanMessage
                    val newFiles = outData?.getInt(com.example.worker.DownloadsFileObserverWorker.KEY_NEW_FILES_INDEXED, 0)
                        ?: outData?.getInt(com.example.worker.FolderMonitorWorker.KEY_NEW_FILES_INDEXED, 0)
                        ?: outData?.getInt(com.example.worker.PdfSyncWorker.KEY_NEW_FILES_INDEXED, 0)
                        ?: 0
                    val newChunks = outData?.getInt(com.example.worker.DownloadsFileObserverWorker.KEY_NEW_CHUNKS_INDEXED, 0)
                        ?: outData?.getInt(com.example.worker.FolderMonitorWorker.KEY_NEW_CHUNKS_INDEXED, 0)
                        ?: outData?.getInt(com.example.worker.PdfSyncWorker.KEY_NEW_CHUNKS_INDEXED, 0)
                        ?: 0

                    FileObserverProgressState(
                        isRunning = false,
                        isMonitoringActive = com.example.worker.FolderMonitorWorker.isMonitoringEnabled(getApplication()),
                        activeTaskName = "Background scan",
                        currentFile = null,
                        currentPhase = "Idle",
                        processedCount = 0,
                        totalCount = 0,
                        percent = 0,
                        lastScanMessage = msg,
                        lastScanTimeMillis = if (latestDone != null) System.currentTimeMillis() else _fileObserverStatus.value.lastScanTimeMillis,
                        newlyIndexedFiles = newFiles,
                        newlyIndexedChunks = newChunks
                    )
                }
            }.collect { status ->
                _fileObserverStatus.value = status
            }
        }
    }

    /** Turns the periodic background scan for new or changed files on or off. */
    fun setAutoScanEnabled(enabled: Boolean) {
        com.example.worker.FolderMonitorWorker.setMonitoringEnabled(getApplication(), enabled)
        _fileObserverStatus.value = _fileObserverStatus.value.copy(isMonitoringActive = enabled)
    }

    fun toggleMultiSelectMode(enable: Boolean? = null) {
        val next = enable ?: !_isMultiSelectMode.value
        _isMultiSelectMode.value = next
        if (!next) {
            _selectedDocumentUris.value = emptySet()
        }
    }

    fun toggleDocumentSelection(fileUri: String) {
        val current = _selectedDocumentUris.value.toMutableSet()
        if (current.contains(fileUri)) {
            current.remove(fileUri)
        } else {
            current.add(fileUri)
        }
        _selectedDocumentUris.value = current
        if (current.isNotEmpty() && !_isMultiSelectMode.value) {
            _isMultiSelectMode.value = true
        }
    }

    fun selectAllVisibleDocuments(uris: List<String>) {
        _selectedDocumentUris.value = uris.toSet()
        if (uris.isNotEmpty()) {
            _isMultiSelectMode.value = true
        }
    }

    fun clearDocumentSelection() {
        _selectedDocumentUris.value = emptySet()
    }

    fun deleteSelectedDocuments(onComplete: ((Int) -> Unit)? = null) {
        val urisToDelete = _selectedDocumentUris.value.toList()
        if (urisToDelete.isEmpty()) return

        viewModelScope.launch {
            repository.deleteDocumentsByUris(urisToDelete)
            _selectedDocumentUris.value = emptySet()
            _isMultiSelectMode.value = false

            // Refresh list
            if (_query.value.isNotBlank() || _selectedTag.value != null) {
                performSearch(_query.value, _searchMode.value, _selectedTag.value)
            } else {
                loadAllIndexedDocuments()
            }

            try {
                com.example.widget.DocuVectorWidget.updateAllWidgets(getApplication())
            } catch (_: Exception) {}

            onComplete?.invoke(urisToDelete.size)
        }
    }

    fun triggerFolderMonitorScan(onResult: ((newFiles: Int, newChunks: Int, status: String) -> Unit)? = null) {
        IndexingController.resume(getApplication())
        viewModelScope.launch {
            _indexingState.value = IndexingState.Progress(
                current = 1,
                total = 1,
                percent = 25,
                currentFile = designatedMonitoredFolder.name,
                currentPhase = "Scanning folder for newly detected files…"
            )
            val workId = com.example.worker.FolderMonitorWorker.triggerImmediateScan(getApplication())
            val workInfo = workManager.getWorkInfoByIdFlow(workId).first { it != null && it.state.isFinished }
                ?: return@launch
            if (workInfo.state == WorkInfo.State.CANCELLED) {
                // Stopped by the user (or replaced by a newer scan): nothing to report.
                if (_indexingState.value is IndexingState.Progress) _indexingState.value = IndexingState.Idle
                return@launch
            }
            val newFiles = workInfo.outputData.getInt(com.example.worker.FolderMonitorWorker.KEY_NEW_FILES_INDEXED, 0)
            val newChunks = workInfo.outputData.getInt(com.example.worker.FolderMonitorWorker.KEY_NEW_CHUNKS_INDEXED, 0)
            val msg = workInfo.outputData.getString(com.example.worker.FolderMonitorWorker.KEY_SCAN_MESSAGE)
                ?: "Monitored folder check complete."
            _folderMonitorStatus.value = msg
            _indexingState.value = IndexingState.Completed(
                chunksCount = newChunks,
                message = msg,
                failedCount = 0
            )
            loadAllIndexedDocuments()
            onResult?.invoke(newFiles, newChunks, msg)
        }
    }

    fun addTestDocumentToMonitoredFolder(
        title: String = "Test_Document",
        content: String = "Offline vector search test file generated for automated folder monitoring."
    ): java.io.File {
        val file = com.example.worker.FolderMonitorWorker.createTestDocumentInMonitoredFolder(
            getApplication(),
            title = title,
            content = content
        )
        triggerFolderMonitorScan()
        return file
    }

    fun loadAllIndexedDocuments() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            try {
                val allDocs = repository.getAllDocuments(_selectedFileType.value, _sortOrder.value)
                _rawSearchResults.value = allDocs
                _searchLatencyMs.value = 0L
            } catch (e: Exception) {
                _rawSearchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun toggleGamingModePause(): Boolean {
        return com.example.engine.HardwareMonitor.toggleIndexingPaused()
    }

    fun setGamingModePause(paused: Boolean) {
        com.example.engine.HardwareMonitor.setIndexingPaused(paused)
    }

    fun toggleIncludeChatBackups(enabled: Boolean) {
        _includeChatBackups.value = enabled
        repository.includeChatBackups = enabled
    }

    fun importChatBackupUri(uri: Uri, displayName: String? = null) {
        com.example.service.ChatBackupIndexingService.startForUri(getApplication(), uri, displayName)
    }

    fun seedSampleChatBackups() {
        viewModelScope.launch {
            val files = repository.seedSampleChatBackup()
            for (file in files) {
                com.example.service.ChatBackupIndexingService.startForUri(
                    getApplication(),
                    Uri.fromFile(file),
                    file.name
                )
            }
        }
    }

    fun deleteSearchHistoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteSearchHistoryItem(id)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
        }
    }

    fun reRunSearchQuery(historyItem: com.example.data.local.SearchHistoryEntity) {
        _query.value = historyItem.query
        val mode = try {
            SearchMode.valueOf(historyItem.searchMode)
        } catch (_: Exception) {
            SearchMode.HYBRID
        }
        _searchMode.value = mode
        _selectedTag.value = historyItem.filterTag
        performSearch(historyItem.query, mode, historyItem.filterTag)
    }

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }

    fun onSearchModeChanged(newMode: SearchMode) {
        if (_searchMode.value != newMode) {
            _searchMode.value = newMode
            performSearch(_query.value, newMode, _selectedTag.value)
        }
    }

    fun selectTag(tag: String?) {
        val newTag = if (tag == "All" || tag.isNullOrBlank()) null else tag
        _selectedTag.value = newTag
        performSearch(_query.value, _searchMode.value, newTag)
    }

    fun addTagToDocument(fileUri: String, tag: String) {
        viewModelScope.launch {
            repository.addTagToDocument(fileUri, tag)
            performSearch(_query.value, _searchMode.value, _selectedTag.value)
        }
    }

    fun removeTagFromDocument(fileUri: String, tag: String) {
        viewModelScope.launch {
            repository.removeTagFromDocument(fileUri, tag)
            performSearch(_query.value, _searchMode.value, _selectedTag.value)
        }
    }

    fun onSelectResultForPreview(result: SearchResult?) {
        _selectedResultForPreview.value = result
    }

    fun setDirectoryUri(uri: Uri) {
        _currentTreeUri.value = uri
        startIndexing(uri)
    }

    fun setFileTypeFilter(fileType: String?) {
        _selectedFileType.value = if (fileType.equals("All", ignoreCase = true) || fileType.isNullOrBlank()) null else fileType
    }

    fun setSortOrder(order: SearchSortOrder) {
        _sortOrder.value = order
    }

    fun setConfidenceTierFilter(tier: String?) {
        _selectedConfidenceTier.value = tier
    }

    fun setDateRange(start: Long?, end: Long?, preset: DateRangePreset = DateRangePreset.CUSTOM) {
        _startDateMillis.value = start
        _endDateMillis.value = end
        _datePreset.value = preset
    }

    fun setDatePreset(preset: DateRangePreset) {
        _datePreset.value = preset
        val (start, end) = calculateDateRangeBounds(preset)
        _startDateMillis.value = start
        _endDateMillis.value = end
    }

    fun clearDateFilter() {
        _startDateMillis.value = null
        _endDateMillis.value = null
        _datePreset.value = DateRangePreset.ALL_TIME
    }

    fun resetFiltersAndSort() {
        _selectedFileType.value = null
        _sortOrder.value = SearchSortOrder.RELEVANCE
        _selectedConfidenceTier.value = null
        _startDateMillis.value = null
        _endDateMillis.value = null
        _datePreset.value = DateRangePreset.ALL_TIME
    }

    private fun performSearch(q: String, mode: SearchMode, tag: String? = _selectedTag.value) {
        searchJob?.cancel()
        if (q.isBlank() && tag.isNullOrBlank()) {
            loadAllIndexedDocuments()
            return
        }

        searchJob = viewModelScope.launch {
            _isSearching.value = true
            val start = System.currentTimeMillis()
            try {
                // Fetch topK = 60 to have ample candidates for interactive filtering and sorting
                // KEYWORD mode: let SQLite FTS pick the candidates, then rank only those by cosine
                // similarity against their quantized embeddings (instead of scanning the whole corpus).
                val isKeyword = mode == SearchMode.KEYWORD && q.isNotBlank()
                var results = repository.search(q, mode, topK = 60, filterTag = tag, semanticScoring = !isKeyword)
                if (isKeyword) {
                    results = semanticallyRankFtsResults(q, results)
                }
                _rawSearchResults.value = results
                _searchLatencyMs.value = System.currentTimeMillis() - start
                if (q.isNotBlank()) {
                    repository.recordSearchQuery(q, mode, results.size, tag)
                }
            } catch (e: Exception) {
                _rawSearchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Enqueues a manual indexing job. Explicitly starting indexing counts as the user turning it back on,
     * so the stopped flag is cleared and the periodic background scan is restored.
     */
    private fun enqueueIndexRequest(request: androidx.work.OneTimeWorkRequest, uniqueName: String) {
        IndexingController.resume(getApplication())
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        observeWork(request.id)
    }

    fun startIndexing(treeUri: Uri) {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.KEY_TREE_URI to treeUri.toString()))
            .addTag(DocumentIndexWorker.TAG)
            .build()

        enqueueIndexRequest(request, "document_indexing_work")
    }

    /**
     * Direct one-tap indexer for the Download folder, bypassing Android 11+ SAF folder privacy blocks.
     */
    fun indexDownloadsDirectory() {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.KEY_INDEX_DOWNLOADS to true))
            .addTag(DocumentIndexWorker.TAG)
            .build()

        enqueueIndexRequest(request, "document_indexing_work")
    }

    /**
     * Direct one-tap indexer for the Android folder (/storage/emulated/0/Android, media & documents),
     * bypassing Android 11+ SAF tree privacy restrictions.
     */
    fun indexAndroidDirectory() {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.KEY_INDEX_ANDROID to true))
            .addTag(DocumentIndexWorker.TAG)
            .build()

        enqueueIndexRequest(request, "document_indexing_work")
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Automatic initial crawl, safe to call on every app resume: each crawl type runs at most once.
     * The whole-storage crawl is remembered across launches (see [IndexingController.isFullStorageCrawlDone]),
     * so reopening the app never restarts a device-wide scan; later changes are picked up by the cheap periodic scan.
     */
    fun autoCrawlOnPermissionGranted() {
        val app = getApplication<Application>()
        if (IndexingController.isStoppedByUser(app)) return
        if (com.example.engine.FailedDocumentRegistry.isSafeModeActive(app)) return
        if (hasAllFilesAccess()) {
            if (fullCrawlRequested || IndexingController.isFullStorageCrawlDone(app)) return
            fullCrawlRequested = true
            indexEntireSystemStorage()
        } else if (!downloadsCrawlRequested) {
            downloadsCrawlRequested = true
            indexDownloadsDirectory()
        }
    }

    /**
     * One-tap indexing for entire system storage: auto-crawls documents and images across the device,
     * ignoring all system files, packages, databases, and videos. There is nothing to choose in a picker.
     */
    fun indexEntireSystemStorage() {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.KEY_INDEX_ENTIRE_SYSTEM to true))
            .addTag(DocumentIndexWorker.TAG)
            .build()

        enqueueIndexRequest(request, "document_indexing_work")
    }

    /**
     * Multi-document picker indexer for files selected by the user inside Download or any directory.
     */
    fun indexSelectedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val uriStrings = uris.map { it.toString() }.toTypedArray()
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.KEY_FILE_URIS to uriStrings))
            .addTag(DocumentIndexWorker.TAG)
            .build()

        enqueueIndexRequest(request, "document_indexing_work")
    }

    /**
     * Ingests a local file picked by the user via ActivityResultContracts.OpenDocument
     * directly through the DocumentFile processing and ingestion pipeline.
     */
    fun processAndIngestDocument(
        uri: Uri,
        onResult: ((isSuccess: Boolean, fileName: String, chunks: Int) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
            _indexingState.value = IndexingState.Progress(
                current = 1,
                total = 1,
                percent = 15,
                currentFile = displayName,
                currentPhase = "Reading via DocumentFile API…"
            )
            val result = repository.ingestDocumentUri(uri) { step, cur, tot ->
                val pct = if (tot > 0) ((cur.toFloat() / tot.toFloat()) * 90).toInt().coerceIn(10, 95) else 50
                _indexingState.value = IndexingState.Progress(
                    current = 1,
                    total = 1,
                    percent = pct,
                    currentFile = displayName,
                    currentPhase = step
                )
            }
            if (result.isSuccess) {
                _indexingState.value = IndexingState.Completed(
                    chunksCount = result.totalChunks,
                    message = "Successfully indexed ${result.fileName} (${result.totalChunks} chunks)",
                    failedCount = 0,
                    errorSummary = null
                )
                onResult?.invoke(true, result.fileName, result.totalChunks)
            } else {
                _indexingState.value = IndexingState.Completed(
                    chunksCount = 0,
                    message = "Failed to index ${result.fileName}: ${result.errorMessage ?: "Unknown error"}",
                    failedCount = 1,
                    errorSummary = result.errorMessage
                )
                onResult?.invoke(false, result.fileName, 0)
            }
            try {
                com.example.widget.DocuVectorWidget.updateAllWidgets(getApplication())
            } catch (_: Exception) {}
        }
    }

    /**
     * Stops all indexing right now (manual jobs, background scans, chat import, model re-index) and keeps
     * background indexing off until [startAllIndexing] is called.
     */
    fun stopIndexing() {
        IndexingController.stopAll(getApplication())
        reindexJob?.cancel()
        _indexingState.value = IndexingState.Idle
    }

    /** Clears the user's "stopped" flag and restores background scanning without starting a job yet. */
    fun allowIndexing() {
        IndexingController.resume(getApplication())
    }

    /**
     * Starts indexing: turns background scanning back on and indexes everything not yet in the index
     * (the whole storage when "All files access" is granted, otherwise the Downloads folder).
     * Files that are already indexed and unchanged are skipped.
     */
    fun startAllIndexing() {
        if (hasAllFilesAccess()) {
            indexEntireSystemStorage()
        } else {
            indexDownloadsDirectory()
        }
        com.example.worker.FolderMonitorWorker.triggerImmediateScan(getApplication())
    }

    fun loadSampleKnowledgeBase() {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.KEY_INDEX_SAMPLE to true))
            .addTag(DocumentIndexWorker.TAG)
            .build()

        enqueueIndexRequest(request, "document_indexing_sample_work")
    }

    fun load100SampleFiles() {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.KEY_INDEX_100_SAMPLES to true))
            .addTag(DocumentIndexWorker.TAG)
            .build()

        enqueueIndexRequest(request, "document_indexing_sample_work")
    }

    /**
     * Programmatically writes 100 dummy text, markdown, and minimal PDF files
     * into context.getExternalFilesDir(null) and kicks off indexing.
     */
    fun seedTestDocuments(onSeeded: ((count: Int, path: String) -> Unit)? = null) {
        viewModelScope.launch {
            val (targetDir, files) = repository.seedTestDocumentsToExternalFilesDir()
            onSeeded?.invoke(files.size, targetDir.absolutePath)
            load100SampleFiles()
        }
    }

    fun isPowerSaveModeActive(): Boolean {
        val powerManager = getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        return powerManager?.isPowerSaveMode == true
    }

    fun reindexCurrent() {
        val uri = _currentTreeUri.value
        if (uri != null) {
            startIndexing(uri)
        } else {
            loadSampleKnowledgeBase()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAll()
            _rawSearchResults.value = emptyList()
            _selectedFileType.value = null
            _sortOrder.value = SearchSortOrder.RELEVANCE
            _selectedConfidenceTier.value = null
            _query.value = ""
            _selectedTag.value = null
            _indexingState.value = IndexingState.Idle
            try {
                com.example.widget.DocuVectorWidget.updateAllWidgets(getApplication())
            } catch (_: Exception) {}
        }
    }

    fun dismissIndexingAlert() {
        _indexingState.value = IndexingState.Idle
    }

    private fun observeWork(workId: java.util.UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo == null) return@collect

                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress
                        val current = progress.getInt(DocumentIndexWorker.KEY_PROGRESS_CURRENT, 0)
                        val total = progress.getInt(DocumentIndexWorker.KEY_PROGRESS_TOTAL, 0)
                        val percent = progress.getInt(DocumentIndexWorker.KEY_PROGRESS_PERCENT, 0)
                        val currentFile = progress.getString(DocumentIndexWorker.KEY_CURRENT_FILE) ?: "Discovering files…"
                        val currentPhase = progress.getString(DocumentIndexWorker.KEY_CURRENT_PHASE) ?: "Indexing"
                        _indexingState.value = IndexingState.Progress(current, total, percent, currentFile, currentPhase)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val chunks = workInfo.outputData.getInt(DocumentIndexWorker.KEY_INDEXED_CHUNKS, 0)
                        val failedCount = workInfo.outputData.getInt(DocumentIndexWorker.KEY_FAILED_COUNT, 0)
                        val errorSummary = workInfo.outputData.getString(DocumentIndexWorker.KEY_ERROR_SUMMARY)

                        val msg = if (failedCount > 0) {
                            "Indexed $chunks chunks. $failedCount file(s) skipped due to errors."
                        } else {
                            "Successfully indexed $chunks chunks. Ready for instant search."
                        }

                        _indexingState.value = IndexingState.Completed(
                            chunksCount = chunks,
                            message = msg,
                            failedCount = failedCount,
                            errorSummary = errorSummary
                        )

                        // Refresh current search if query is non-empty
                        if (_query.value.isNotBlank()) {
                            performSearch(_query.value, _searchMode.value)
                        }

                        try {
                            com.example.widget.DocuVectorWidget.updateAllWidgets(getApplication())
                        } catch (_: Exception) {}
                    }
                    WorkInfo.State.FAILED -> {
                        val err = workInfo.outputData.getString(DocumentIndexWorker.KEY_ERROR_SUMMARY)
                            ?: "Indexing failed. Please check storage permissions."
                        _indexingState.value = IndexingState.Error(err)
                    }
                    WorkInfo.State.CANCELLED -> {
                        _indexingState.value = IndexingState.Idle
                    }
                    else -> {}
                }
            }
        }
    }

    // --- Updater Actions ---
    fun setShowUpdateSheet(show: Boolean) {
        _showUpdateSheet.value = show
    }

    fun checkForUpdates(forceCheck: Boolean = true) {
        if (_isCheckingForUpdates.value) return
        _isCheckingForUpdates.value = true
        _updateCheckResult.value = UpdateCheckResult.Checking

        viewModelScope.launch {
            try {
                val result = updateChecker.checkForUpdates(updatePreferences.config.value, forceCheck)
                updatePreferences.recordLastCheckTime()
                _updateCheckResult.value = result

                if (result is UpdateCheckResult.UpdateAvailable) {
                    // Reset dismissed if forced check
                    if (forceCheck) {
                        _dismissedUpdateCode.value = 0
                    }
                }
            } catch (e: Exception) {
                _updateCheckResult.value = UpdateCheckResult.Error(
                    e.localizedMessage ?: "Failed to check for updates",
                    e
                )
            } finally {
                _isCheckingForUpdates.value = false
            }
        }
    }

    fun startDownloadUpdate(info: AppUpdateInfo) {
        viewModelScope.launch {
            updateDownloader.startDownload(info)
        }
    }

    fun cancelDownloadUpdate() {
        updateDownloader.cancelDownload()
    }

    fun installDownloadedApk(apkFile: File) {
        AppUpdateInstaller.installApk(getApplication(), apkFile)
    }

    fun updateConfig(newConfig: UpdateConfig) {
        updatePreferences.updateConfig(
            sourceType = newConfig.sourceType,
            githubRepo = newConfig.githubRepo,
            customManifestUrl = newConfig.customManifestUrl,
            autoCheckEnabled = newConfig.autoCheckEnabled,
            checkOnWifiOnly = newConfig.checkOnWifiOnly
        )
    }

    fun simulateUpdate() {
        val simResult = updateChecker.checkSimulationUpdate(
            com.example.BuildConfig.VERSION_CODE,
            com.example.BuildConfig.VERSION_NAME
        )
        _dismissedUpdateCode.value = 0
        _updateCheckResult.value = simResult
        _showUpdateSheet.value = true
    }

    fun dismissUpdateBanner(versionCode: Int) {
        _dismissedUpdateCode.value = versionCode
        updatePreferences.dismissVersion(versionCode)
    }
}
