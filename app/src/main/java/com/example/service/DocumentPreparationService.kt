package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkDao
import com.example.data.local.DocumentChunkEntity
import com.example.engine.DocumentParser
import com.example.engine.OnDeviceEmbeddingEngine
import com.example.engine.model.UnifiedEmbeddingManager
import com.example.engine.ParsedChunk
import com.example.engine.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Android Service that reads text from local files using the DocumentFile API
 * and prepares it for embedding generation and database storage.
 *
 * Capabilities:
 * - Uses androidx.documentfile.provider.DocumentFile for robust local file & SAF access
 * - Extracts and normalizes text from Text, Markdown, PDF, DOCX, PPTX, HTML, JSON, CSV, and Images
 * - Segments documents into overlapping semantic chunks with SHA-256 content hashes
 * - De-duplicates chunks incrementally against existing Room database records
 * - Batches chunks for high-throughput embedding passes on the LiteRT/TFLite engine
 * - Persists prepared vectors and full-text search entries into SQLite via Room DAO
 * - Emits real-time IngestionEvents via SharedFlow for UI/Worker progress tracking
 */
class DocumentPreparationService : Service() {

    companion object {
        private const val TAG = "DocPrepService"

        @Volatile
        private var instance: DocumentPreparationService? = null

        fun getInstance(context: Context): DocumentPreparationService {
            return instance ?: synchronized(this) {
                instance ?: DocumentPreparationService().apply {
                    initialize(context.applicationContext)
                    instance = this
                }
            }
        }
    }

    data class PreparedChunk(
        val index: Int,
        val text: String,
        val hash: String,
        val characterCount: Int,
        val page: Int? = null,
        val pageEnd: Int? = null
    )

    data class PreparedDocument(
        val fileUri: String,
        val fileName: String,
        val mimeType: String?,
        val fileSize: Long,
        val lastModified: Long,
        val fullText: String,
        val chunks: List<PreparedChunk>
    )

    sealed interface PreparationResult {
        data class Success(val preparedDocument: PreparedDocument) : PreparationResult
        data class Failure(val fileName: String, val fileUri: String, val reason: String) : PreparationResult
    }

    data class IngestionResult(
        val fileName: String,
        val fileUri: String,
        val isSuccess: Boolean,
        val totalChunks: Int,
        val newChunksEmbedded: Int,
        val errorMessage: String? = null
    )

    sealed interface IngestionEvent {
        data class Started(val fileName: String, val fileUri: String) : IngestionEvent
        data class TextExtracted(val fileName: String, val charCount: Int, val chunkCount: Int) : IngestionEvent
        data class EmbeddingBatch(val fileName: String, val currentBatch: Int, val totalBatches: Int, val percent: Int) : IngestionEvent
        data class StoredInDatabase(val fileName: String, val chunksStored: Int) : IngestionEvent
        data class Failed(val fileName: String, val error: String) : IngestionEvent
        data class Completed(val fileName: String, val chunksIndexed: Int) : IngestionEvent
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var documentParser: DocumentParser? = null
    private var modelManager: UnifiedEmbeddingManager? = null
    private var chunkDao: DocumentChunkDao? = null

    private val _ingestionEvents = MutableSharedFlow<IngestionEvent>(extraBufferCapacity = 64)
    val ingestionEvents: SharedFlow<IngestionEvent> = _ingestionEvents.asSharedFlow()

    inner class LocalBinder : Binder() {
        fun getService(): DocumentPreparationService = this@DocumentPreparationService
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        instance = this
        initialize(applicationContext)
        Log.i(TAG, "DocumentPreparationService created.")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "DocumentPreparationService destroyed.")
    }

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        val ctx = appContext ?: context
        if (documentParser == null) {
            documentParser = DocumentParser(ctx)
        }
        if (modelManager == null) {
            modelManager = UnifiedEmbeddingManager.getInstance(ctx)
        }
        if (chunkDao == null) {
            chunkDao = AppDatabase.getInstance(ctx).documentChunkDao()
        }
    }

    /**
     * Reads text from a local file using the DocumentFile API and prepares it into
     * structured semantic chunks ready for embedding and database storage.
     */
    suspend fun readAndPrepareDocument(docFile: DocumentFile): PreparationResult = withContext(Dispatchers.IO) {
        val parser = documentParser ?: throw IllegalStateException("DocumentParser not initialized")
        val fileName = docFile.name ?: "Unknown"
        val fileUri = docFile.uri.toString()

        if (!docFile.exists() || !docFile.canRead()) {
            return@withContext PreparationResult.Failure(
                fileName = fileName,
                fileUri = fileUri,
                reason = "File does not exist or cannot be read"
            )
        }

        val parseResult = parser.parseDocumentSafely(docFile)
        if (parseResult is ParseResult.Failure) {
            return@withContext PreparationResult.Failure(
                fileName = fileName,
                fileUri = fileUri,
                reason = parseResult.reason
            )
        }

        val parsed = (parseResult as ParseResult.Success).document
        val preparedChunks = parsed.chunks.map { c ->
            PreparedChunk(
                index = c.index,
                text = c.text,
                hash = c.hash,
                characterCount = c.text.length,
                page = c.page,
                pageEnd = c.pageEnd
            )
        }

        PreparationResult.Success(
            PreparedDocument(
                fileUri = parsed.fileUri,
                fileName = parsed.fileName,
                mimeType = parsed.mimeType ?: docFile.type,
                fileSize = docFile.length(),
                lastModified = docFile.lastModified(),
                fullText = parsed.fullText,
                chunks = preparedChunks
            )
        )
    }

    /**
     * Reads a DocumentFile from a local Uri and prepares it.
     */
    suspend fun readAndPrepareUri(uri: Uri): PreparationResult = withContext(Dispatchers.IO) {
        val ctx = appContext ?: throw IllegalStateException("Service not initialized")
        val docFile = if (uri.scheme == "file") {
            val path = uri.path ?: uri.toString().removePrefix("file://")
            DocumentFile.fromFile(File(path))
        } else {
            DocumentFile.fromSingleUri(ctx, uri)
        } ?: return@withContext PreparationResult.Failure("Unknown", uri.toString(), "Invalid DocumentFile URI")

        readAndPrepareDocument(docFile)
    }

    /**
     * Ingests a local file from a Uri, generates embeddings and persists into Room database.
     */
    suspend fun ingestAndStoreUri(
        uri: Uri,
        onProgress: ((step: String, current: Int, total: Int) -> Unit)? = null
    ): IngestionResult = withContext(Dispatchers.IO) {
        val ctx = appContext ?: throw IllegalStateException("Service not initialized")
        val docFile = if (uri.scheme == "file") {
            val path = uri.path ?: uri.toString().removePrefix("file://")
            DocumentFile.fromFile(File(path))
        } else {
            DocumentFile.fromSingleUri(ctx, uri)
        } ?: return@withContext IngestionResult(
            fileName = "Unknown",
            fileUri = uri.toString(),
            isSuccess = false,
            totalChunks = 0,
            newChunksEmbedded = 0,
            errorMessage = "Invalid DocumentFile URI: $uri"
        )
        ingestAndStoreDocument(docFile, onProgress)
    }

    /**
     * End-to-end ingestion pipeline:
     * 1. Reads text using DocumentFile API
     * 2. Prepares semantic chunks with SHA-256 hashes
     * 3. Checks existing database records for incremental de-duplication
     * 4. Generates dense embeddings locally on-device
     * 5. Atomically commits entities to SQLite Room database with FTS index
     */
    suspend fun ingestAndStoreDocument(
        docFile: DocumentFile,
        onProgress: ((step: String, current: Int, total: Int) -> Unit)? = null
    ): IngestionResult = withContext(Dispatchers.IO) {
        val fileName = docFile.name ?: "Unknown"
        val fileUri = docFile.uri.toString()

        _ingestionEvents.emit(IngestionEvent.Started(fileName, fileUri))
        onProgress?.invoke("Reading $fileName via DocumentFile API…", 0, 100)

        val prepResult = readAndPrepareDocument(docFile)
        if (prepResult is PreparationResult.Failure) {
            _ingestionEvents.emit(IngestionEvent.Failed(fileName, prepResult.reason))
            return@withContext IngestionResult(
                fileName = fileName,
                fileUri = fileUri,
                isSuccess = false,
                totalChunks = 0,
                newChunksEmbedded = 0,
                errorMessage = prepResult.reason
            )
        }

        val prepared = (prepResult as PreparationResult.Success).preparedDocument
        _ingestionEvents.emit(IngestionEvent.TextExtracted(fileName, prepared.fullText.length, prepared.chunks.size))

        val dao = chunkDao ?: throw IllegalStateException("Database DAO not initialized")
        val manager = modelManager ?: throw IllegalStateException("Embedding engine not initialized")

        // Incremental check: Compare chunk hashes against existing database records
        val existingChunks = dao.getChunksForFile(prepared.fileUri)
        val existingHashMap = existingChunks.associateBy { it.hash }

        val chunksToEmbed = mutableListOf<PreparedChunk>()
        val finalEntities = mutableListOf<DocumentChunkEntity>()

        for (chunk in prepared.chunks) {
            val existing = existingHashMap[chunk.hash]
            if (existing != null) {
                finalEntities.add(existing)
            } else {
                chunksToEmbed.add(chunk)
            }
        }

        // Generate embeddings for new/modified chunks
        if (chunksToEmbed.isNotEmpty()) {
            val batchSize = OnDeviceEmbeddingEngine.DEFAULT_BATCH_SIZE
            val totalBatches = (chunksToEmbed.size + batchSize - 1) / batchSize

            for (b in 0 until totalBatches) {
                com.example.engine.HardwareMonitor.checkPausePoint()
                val start = b * batchSize
                val end = minOf(start + batchSize, chunksToEmbed.size)
                val batchList = chunksToEmbed.subList(start, end)
                val percent = (((b + 1).toFloat() / totalBatches.toFloat()) * 90).toInt()

                onProgress?.invoke("Embedding chunks ${start + 1}-$end of ${chunksToEmbed.size}…", percent, 100)
                _ingestionEvents.emit(IngestionEvent.EmbeddingBatch(fileName, b + 1, totalBatches, percent))

                val texts = batchList.map { it.text }
                val tagged = manager.embedBatchTagged(texts, isQuery = false, batchSize = batchSize)
                val embeddings = tagged.vectors

                for (i in batchList.indices) {
                    val c = batchList[i]
                    val emb = embeddings[i]
                    val blob = com.example.engine.VectorSimilarityUtils.floatArrayToByteArray(emb)

                    finalEntities.add(
                        DocumentChunkEntity(
                            fileUri = prepared.fileUri,
                            fileName = prepared.fileName,
                            chunkIndex = c.index,
                            chunkText = c.text,
                            hash = c.hash,
                            timestamp = if (prepared.lastModified > 0) prepared.lastModified else System.currentTimeMillis(),
                            embeddingBlob = blob,
                            metadata = com.example.engine.extraction.ChunkMetadata.encode(c.page, c.pageEnd, tagged.modelId)
                        )
                    )
                }
            }
        }

        // Persist to Room database with SQLite FTS index
        onProgress?.invoke("Saving vectors and full-text index to SQLite…", 95, 100)
        dao.deleteFileRecord(prepared.fileUri)
        dao.insertChunksWithFts(finalEntities)

        _ingestionEvents.emit(IngestionEvent.StoredInDatabase(fileName, finalEntities.size))
        _ingestionEvents.emit(IngestionEvent.Completed(fileName, finalEntities.size))
        onProgress?.invoke("Finished $fileName", 100, 100)

        IngestionResult(
            fileName = fileName,
            fileUri = fileUri,
            isSuccess = true,
            totalChunks = finalEntities.size,
            newChunksEmbedded = chunksToEmbed.size
        )
    }

    /**
     * Traverses a DocumentFile directory and processes all supported regular documents and images.
     */
    suspend fun ingestDirectory(
        directory: DocumentFile,
        onFileProgress: ((current: Int, total: Int, currentFile: String, step: String) -> Unit)? = null
    ): List<IngestionResult> = withContext(Dispatchers.IO) {
        val parser = documentParser ?: throw IllegalStateException("DocumentParser not initialized")
        val files = parser.scanDirectory(directory.uri)
        val results = mutableListOf<IngestionResult>()

        files.forEachIndexed { index, docFile ->
            val fileName = docFile.name ?: "Document"
            val result = ingestAndStoreDocument(docFile) { step, _, _ ->
                onFileProgress?.invoke(index + 1, files.size, fileName, step)
            }
            results.add(result)
        }

        results
    }
}
