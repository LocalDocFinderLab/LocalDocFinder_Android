package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import com.example.engine.DocumentParser
import com.example.engine.ExecutionBackend
import com.example.engine.HybridSearchEngine
import com.example.engine.OnDeviceEmbeddingEngine
import com.example.engine.ParseResult
import com.example.engine.SearchMode
import com.example.engine.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class IndexDocResult(
    val fileName: String,
    val fileUri: String,
    val isSuccess: Boolean,
    val chunksIndexed: Int,
    val errorMessage: String? = null
)

data class DocumentPreviewContent(
    val fileUri: String,
    val fileName: String,
    val mimeType: String?,
    val isPdf: Boolean,
    val isImage: Boolean = false,
    val fullText: String,
    val pdfPageCount: Int = 0,
    val imageBitmap: Bitmap? = null
)

/** One chunk of an indexed document with the overlap shared with the previous chunk trimmed away. */
data class DocumentDetailChunk(
    val chunkIndex: Int,
    val text: String
)

/**
 * Read-only view of an indexed document: its stored content plus metadata, for the detail sheet.
 */
data class DocumentDetail(
    val fileUri: String,
    val fileName: String,
    val chunks: List<DocumentDetailChunk>,
    val totalCharacters: Int,
    val wordCount: Int,
    val indexedAtMillis: Long,
    val sourceModifiedMillis: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val location: String,
    val tags: List<String>
)

class DocumentRepository(
    private val context: Context
) {
    private val database = AppDatabase.getInstance(context)
    private val dao = database.documentChunkDao()
    private val searchHistoryDao = database.searchHistoryDao()
    val embeddingEngine = OnDeviceEmbeddingEngine(context)
    val modelManager = com.example.engine.model.UnifiedEmbeddingManager(context, embeddingEngine)
    val documentParser = DocumentParser(context)
    val documentPreparationService = com.example.service.DocumentPreparationService.getInstance(context)
    val hybridSearchEngine = HybridSearchEngine(dao, embeddingEngine, modelManager)
    val pixelOptimizer = embeddingEngine.pixelOptimizer

    val totalChunksCount: Flow<Int> = dao.getTotalChunksCount()
    val totalFilesCount: Flow<Int> = dao.getTotalFilesCount()
    val indexedFiles: Flow<List<String>> = dao.getIndexedFiles()
    val allTags: Flow<List<String>> = dao.getAllDistinctTags()
    val recentSearches: Flow<List<com.example.data.local.SearchHistoryEntity>> = searchHistoryDao.getRecentSearchesFlow(30)

    var includeChatBackups: Boolean
        get() = documentParser.includeChatBackups
        set(value) {
            documentParser.includeChatBackups = value
        }

    fun getBackend(): ExecutionBackend = embeddingEngine.getBackend()
    fun getPixelProfile() = embeddingEngine.getPixelProfile()

    suspend fun recordSearchQuery(
        query: String,
        mode: SearchMode,
        resultCount: Int,
        filterTag: String? = null
    ) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length >= 2) {
            // Remove previous duplicate query if exists so latest is at the top
            searchHistoryDao.deleteByQuery(trimmed)
            searchHistoryDao.insertSearch(
                com.example.data.local.SearchHistoryEntity(
                    query = trimmed,
                    searchMode = mode.name,
                    resultCount = resultCount,
                    timestamp = System.currentTimeMillis(),
                    filterTag = filterTag
                )
            )
        }
    }

    suspend fun deleteSearchHistoryItem(id: Long) = withContext(Dispatchers.IO) {
        searchHistoryDao.deleteById(id)
    }

    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searchHistoryDao.clearAll()
    }

    suspend fun getTagsForFile(fileUri: String): List<String> = dao.getTagsForFile(fileUri)

    suspend fun addTagToDocument(fileUri: String, tag: String) = withContext(Dispatchers.IO) {
        val currentTags = dao.getTagsForFile(fileUri)
        val cleanTag = tag.trim()
        if (cleanTag.isNotBlank() && !currentTags.contains(cleanTag)) {
            val updated = currentTags + cleanTag
            dao.setTagsForFile(fileUri, updated)
        }
    }

    suspend fun removeTagFromDocument(fileUri: String, tag: String) = withContext(Dispatchers.IO) {
        val currentTags = dao.getTagsForFile(fileUri)
        val cleanTag = tag.trim()
        val updated = currentTags.filter { !it.equals(cleanTag, ignoreCase = true) }
        dao.setTagsForFile(fileUri, updated)
    }

    suspend fun ingestDocumentUri(
        uri: Uri,
        onProgress: ((step: String, current: Int, total: Int) -> Unit)? = null
    ) = documentPreparationService.ingestAndStoreUri(uri, onProgress)

    suspend fun search(
        query: String,
        mode: SearchMode,
        topK: Int = 30,
        filterTag: String? = null,
        fileTypeFilter: String? = null,
        sortOrder: com.example.engine.SearchSortOrder = com.example.engine.SearchSortOrder.RELEVANCE,
        semanticScoring: Boolean = true
    ): List<SearchResult> {
        return hybridSearchEngine.search(query, mode, topK, filterTag, fileTypeFilter, sortOrder, semanticScoring)
    }

    /**
     * Embeds a search query with the active embedding model (same model used for the stored chunk vectors).
     */
    suspend fun embedQuery(query: String): FloatArray = modelManager.embedText(query, isQuery = true)

    /**
     * Loads only the (quantized) embedding BLOBs for the given chunk ids.
     * Batched to stay below SQLite's bound-variable limit.
     */
    suspend fun loadEmbeddingBlobs(chunkIds: List<Long>): Map<Long, ByteArray> = withContext(Dispatchers.IO) {
        if (chunkIds.isEmpty()) return@withContext emptyMap()
        val blobs = HashMap<Long, ByteArray>(chunkIds.size)
        for (batch in chunkIds.distinct().chunked(500)) {
            for (row in dao.getEmbeddingsForChunkIds(batch)) {
                blobs[row.id] = row.embeddingBlob
            }
        }
        blobs
    }

    /**
     * Safely indexes a document with detailed progress and error reporting.
     */
    suspend fun indexDocumentSafely(
        docFile: DocumentFile,
        onSubProgress: ((step: String, current: Int, total: Int) -> Unit)? = null
    ): IndexDocResult = withContext(Dispatchers.IO) {
        val fileName = docFile.name ?: "Unknown"
        val fileUri = docFile.uri.toString()

        onSubProgress?.invoke("Parsing $fileName…", 0, 100)
        val parseResult = documentParser.parseDocumentSafely(docFile)
        if (parseResult is ParseResult.Failure) {
            return@withContext IndexDocResult(
                fileName = fileName,
                fileUri = fileUri,
                isSuccess = false,
                chunksIndexed = 0,
                errorMessage = parseResult.reason
            )
        }

        val parsed = (parseResult as ParseResult.Success).document
        val existingChunks = dao.getChunksForFile(parsed.fileUri)
        val existingHashMap = existingChunks.associateBy { it.hash }

        val chunksToEmbed = mutableListOf<com.example.engine.ParsedChunk>()
        val finalEntities = mutableListOf<DocumentChunkEntity>()

        for (chunk in parsed.chunks) {
            val existing = existingHashMap[chunk.hash]
            if (existing != null) {
                finalEntities.add(existing)
            } else {
                chunksToEmbed.add(chunk)
            }
        }

        if (chunksToEmbed.isNotEmpty()) {
            val batchSize = com.example.engine.OnDeviceEmbeddingEngine.DEFAULT_BATCH_SIZE
            val totalBatches = (chunksToEmbed.size + batchSize - 1) / batchSize

            for (b in 0 until totalBatches) {
                com.example.engine.HardwareMonitor.checkPausePoint()
                val start = b * batchSize
                val end = minOf(start + batchSize, chunksToEmbed.size)
                val batchChunks = chunksToEmbed.subList(start, end)

                onSubProgress?.invoke(
                    "NPU Batch: embedding chunks ${start + 1}-$end of ${chunksToEmbed.size} as single tensor",
                    end,
                    chunksToEmbed.size
                )

                val texts = batchChunks.map { it.text }
                val activeModelName = modelManager.getActiveModel().shortName
                onSubProgress?.invoke(
                    "Model [$activeModelName]: embedding chunks ${start + 1}-$end of ${chunksToEmbed.size}",
                    end,
                    chunksToEmbed.size
                )
                val embeddings = modelManager.embedBatch(texts, isQuery = false, batchSize = batchSize)

                for (i in batchChunks.indices) {
                    val c = batchChunks[i]
                    val emb = embeddings[i]
                    val blob = com.example.engine.VectorSimilarityUtils.floatArrayToByteArray(emb)

                    finalEntities.add(
                        DocumentChunkEntity(
                            fileUri = parsed.fileUri,
                            fileName = parsed.fileName,
                            chunkIndex = c.index,
                            chunkText = c.text,
                            hash = c.hash,
                            timestamp = if (docFile.lastModified() > 0) docFile.lastModified() else System.currentTimeMillis(),
                            embeddingBlob = blob
                        )
                    )
                }
            }
        }

        onSubProgress?.invoke("Writing vectors to SQLite…", 95, 100)
        dao.deleteFileRecord(parsed.fileUri)
        dao.insertChunksWithFts(finalEntities)
        onSubProgress?.invoke("Finished $fileName", 100, 100)

        IndexDocResult(
            fileName = fileName,
            fileUri = fileUri,
            isSuccess = true,
            chunksIndexed = finalEntities.size
        )
    }

    suspend fun indexDocument(docFile: DocumentFile, onProgress: ((Int, Int) -> Unit)? = null): Int {
        val result = indexDocumentSafely(docFile) { _, cur, tot -> onProgress?.invoke(cur, tot) }
        return result.chunksIndexed
    }

    /**
     * Loads the full indexed content and metadata of a document from the local index (no file access needed
     * for the text, so it works even if the original file has since been moved or deleted).
     */
    suspend fun loadDocumentDetail(fileUriStr: String, fileName: String): DocumentDetail = withContext(Dispatchers.IO) {
        val dbChunks = dao.getChunksForFile(fileUriStr)
        val stitchedTexts = com.example.engine.ChunkStitcher.stitch(dbChunks.map { it.chunkText })
        val chunks = dbChunks.mapIndexed { i, c -> DocumentDetailChunk(c.chunkIndex, stitchedTexts[i]) }
        val fullText = chunks.joinToString(" ") { it.text }

        val uri = Uri.parse(fileUriStr)
        var sizeBytes: Long? = null
        var modifiedMillis: Long? = null
        try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                if (f.exists()) {
                    sizeBytes = f.length()
                    modifiedMillis = f.lastModified().takeIf { it > 0L }
                }
            } else {
                DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() }?.let {
                    sizeBytes = it.length().takeIf { len -> len > 0L }
                    modifiedMillis = it.lastModified().takeIf { ts -> ts > 0L }
                }
            }
        } catch (_: Exception) {
        }

        val mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }

        val dbTags = dao.getTagsForFile(fileUriStr)
        val tags = dbTags.ifEmpty {
            dbChunks.firstOrNull()?.tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        }

        DocumentDetail(
            fileUri = fileUriStr,
            fileName = fileName,
            chunks = chunks,
            totalCharacters = fullText.length,
            wordCount = if (fullText.isBlank()) 0 else fullText.trim().split(Regex("""\s+""")).size,
            indexedAtMillis = dbChunks.maxOfOrNull { it.timestamp } ?: 0L,
            sourceModifiedMillis = modifiedMillis,
            sizeBytes = sizeBytes,
            mimeType = mime,
            location = if (uri.scheme == "file") (uri.path ?: fileUriStr) else Uri.decode(fileUriStr),
            tags = tags
        )
    }

    /**
     * Loads full document content for in-app preview.
     */
    suspend fun loadDocumentPreview(fileUriStr: String, fileName: String): DocumentPreviewContent = withContext(Dispatchers.IO) {
        val uri = Uri.parse(fileUriStr)
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isPdf = ext == "pdf"
        val isImage = ext in com.example.engine.ImageMetadataExtractor.IMAGE_EXTENSIONS

        var pageCount = 0
        var textContent = ""
        var imageBmp: Bitmap? = null

        if (isImage) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    imageBmp = android.graphics.BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {}
        }

        if (isPdf) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val renderer = PdfRenderer(pfd)
                    pageCount = renderer.pageCount
                    renderer.close()
                }
            } catch (_: Exception) {}
        }

        // Try extracting preview text
        val docFile = DocumentFile.fromSingleUri(context, uri)
        if (docFile != null) {
            val parseRes = documentParser.parseDocumentSafely(docFile)
            if (parseRes is ParseResult.Success) {
                textContent = parseRes.document.fullText
            }
        }

        if (textContent.isBlank()) {
            // Fallback to chunks saved in SQLite database
            val dbChunks = dao.getChunksForFile(fileUriStr)
            if (dbChunks.isNotEmpty()) {
                textContent = dbChunks.joinToString("\n\n") { it.chunkText }
            }
        }

        DocumentPreviewContent(
            fileUri = fileUriStr,
            fileName = fileName,
            mimeType = context.contentResolver.getType(uri),
            isPdf = isPdf,
            isImage = isImage,
            fullText = textContent,
            pdfPageCount = pageCount,
            imageBitmap = imageBmp
        )
    }

    /**
     * Generates and indexes 100 sample documents, technical papers, and images offline.
     */
    suspend fun createAndIndex100SampleFiles(
        onProgress: (current: Int, total: Int, currentFile: String, subPercent: Int) -> Unit
    ): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        val sampleDocsDir = File(context.filesDir, "sample_documents_100").apply { mkdirs() }
        val generatedFiles = com.example.engine.SampleDocumentGenerator.generate100SampleFiles(sampleDocsDir)

        var totalChunks = 0
        val errors = mutableListOf<String>()

        generatedFiles.forEachIndexed { index, file ->
            val fileName = file.name
            val docFile = DocumentFile.fromFile(file)

            val res = indexDocumentSafely(docFile) { _, cur, tot ->
                val sub = if (tot > 0) (cur * 100) / tot else 50
                onProgress(index + 1, generatedFiles.size, fileName, sub)
            }

            if (res.isSuccess) {
                totalChunks += res.chunksIndexed
                val inferredTags = inferTagsForFilename(fileName)
                if (inferredTags.isNotEmpty()) {
                    dao.setTagsForFile(docFile.uri.toString(), inferredTags)
                }
            } else {
                errors.add("$fileName: ${res.errorMessage}")
            }
        }

        Pair(totalChunks, errors)
    }

    private fun inferTagsForFilename(name: String): List<String> {
        val lower = name.lowercase()
        val tags = mutableListOf<String>()
        if (lower.contains("transformer") || lower.contains("neural") || lower.contains("embedding") || lower.contains("litert") || lower.contains("quantiz") || lower.contains("lora") || lower.contains("vision")) {
            tags.add("AI & ML")
        }
        if (lower.contains("raft") || lower.contains("consensus") || lower.contains("paxos") || lower.contains("distribut") || lower.contains("microservice")) {
            tags.add("Distributed")
        }
        if (lower.contains("quantum") || lower.contains("qubit") || lower.contains("shor") || lower.contains("superposition")) {
            tags.add("Quantum")
        }
        if (lower.contains("mrna") || lower.contains("crispr") || lower.contains("gene") || lower.contains("genomic") || lower.contains("bio")) {
            tags.add("Bio & Health")
        }
        if (lower.contains("btree") || lower.contains("lsm") || lower.contains("sqlite") || lower.contains("storage") || lower.contains("fts")) {
            tags.add("Storage & DB")
        }
        if (lower.endsWith(".pdf")) {
            tags.add("PDF")
        } else if (lower.endsWith(".md")) {
            tags.add("Markdown")
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
            tags.add("Image")
        }
        if (tags.isEmpty()) {
            tags.add("Document")
        }
        return tags.distinct()
    }

    /**
     * Seeds 100 dummy text, markdown, and minimal PDF files into context.getExternalFilesDir(null)
     * so the app has local documents to index immediately without downloading.
     */
    suspend fun seedTestDocumentsToExternalFilesDir(): Pair<File, List<File>> = withContext(Dispatchers.IO) {
        com.example.engine.SampleDocumentGenerator.seed100TestDocuments(context)
    }

    /**
     * Renders a specific page of a PDF file to a Bitmap.
     */
    suspend fun renderPdfPage(fileUriStr: String, pageIndex: Int, targetWidth: Int = 1080): Bitmap? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(fileUriStr)
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val renderer = PdfRenderer(pfd)
                if (pageIndex in 0 until renderer.pageCount) {
                    val page = renderer.openPage(pageIndex)
                    val ratio = page.height.toFloat() / page.width.toFloat()
                    val targetHeight = (targetWidth * ratio).toInt()
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    renderer.close()
                    return@withContext bitmap
                }
                renderer.close()
            }
        } catch (_: Exception) {}
        null
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    suspend fun deleteDocumentsByUris(fileUris: List<String>) = withContext(Dispatchers.IO) {
        dao.deleteFileRecords(fileUris)
    }

    suspend fun deleteDocumentByUri(fileUri: String) = withContext(Dispatchers.IO) {
        dao.deleteFileRecord(fileUri)
    }

    suspend fun getIndexedFileUrisDirect(): List<String> = withContext(Dispatchers.IO) {
        dao.getIndexedFilesDirect()
    }

    /**
     * Retrieves all documents currently indexed in the local database as SearchResults,
     * allowing users to browse their complete offline knowledge base even without a query.
     */
    suspend fun getAllDocuments(
        fileTypeFilter: String? = null,
        sortOrder: com.example.engine.SearchSortOrder = com.example.engine.SearchSortOrder.RELEVANCE
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val allChunks = dao.getAllChunks()
        if (allChunks.isEmpty()) return@withContext emptyList()

        val uniqueUris = allChunks.map { it.fileUri }.distinct()
        val tagsByUri = HashMap<String, List<String>>()
        for (uri in uniqueUris) {
            tagsByUri[uri] = dao.getTagsForFile(uri)
        }

        // Group by fileUri so each document appears once in the document list
        val groupedByFile = allChunks.groupBy { it.fileUri }
        val docResults = groupedByFile.map { (uri, chunks) ->
            val firstChunk = chunks.minByOrNull { it.chunkIndex } ?: chunks.first()
            val resolvedTags = tagsByUri[uri]?.takeIf { it.isNotEmpty() }
                ?: if (firstChunk.tags.isNotBlank()) firstChunk.tags.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()

            SearchResult(
                chunkId = firstChunk.id,
                fileUri = uri,
                fileName = firstChunk.fileName,
                chunkIndex = firstChunk.chunkIndex,
                chunkText = firstChunk.chunkText,
                snippet = firstChunk.chunkText.take(240) + if (firstChunk.chunkText.length > 240) "…" else "",
                highlightedTerms = emptyList(),
                cosineSimilarity = 1.0f,
                vectorRank = null,
                ftsRank = null,
                rrfScore = 1.0f,
                latencyMs = 0L,
                tags = resolvedTags,
                timestamp = firstChunk.timestamp,
                fileSize = chunks.sumOf { it.chunkText.length }.toLong()
            )
        }

        com.example.engine.SearchFilterState(selectedFileType = fileTypeFilter, sortOrder = sortOrder)
            .apply(docResults)
    }

    /**
     * Generates a sample knowledge base offline in app files directory and indexes it.
     */
    suspend fun createAndIndexSampleKnowledgeBase(
        onProgress: (current: Int, total: Int, currentFile: String, subProgressPercent: Int) -> Unit
    ): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        val sampleDocsDir = File(context.filesDir, "sample_documents").apply { mkdirs() }

        val sampleFiles = listOf(
            SampleDoc(
                fileName = "LiteRT_Embedded_AI_Architecture.md",
                tags = listOf("AI", "EdgeComputing", "Hardware"),
                content = """
                    # LiteRT & On-Device Vector Embeddings
                    
                    LiteRT (formerly TensorFlow Lite Runtime) is Google's high-performance on-device inference platform.
                    It enables developers to run deep neural networks directly on edge devices with zero cloud latency.
                    
                    ## Hardware Acceleration & Delegates
                    - Qualcomm QNN Delegate: Targets the Hexagon NPU (HTP) on Snapdragon platforms, offering sub-5ms INT8 tensor operations.
                    - GPU Delegate: Utilizes OpenCL and Vulkan compute pipelines for high-throughput matrix multiplications.
                    - CPU XNNPACK: Delivers optimized NEON SIMD kernels for ARM Cortex-A architectures when dedicated accelerators are unavailable.
                    
                    ## Quantization & Embedding Models
                    Text embedding models like all-MiniLM-L6-v2 and bge-small-en compress dense 384-dimensional vector representations.
                    Post-training INT8 quantization reduces model storage from 130MB down to ~30MB with negligible semantic degradation.
                    L2 normalization transforms vector cosine similarity into a straightforward inner dot-product.
                """.trimIndent()
            ),
            SampleDoc(
                fileName = "SQLite_Vector_and_FTS5_Hybrid_Search.txt",
                tags = listOf("Database", "Search", "Architecture"),
                content = """
                    SQLite Hybrid Search Engine Architecture
                    
                    Combining Full-Text Search (FTS) with Dense Vector Retrieval bridges the semantic gap while preserving lexical precision.
                    
                    1. Keyword Search via SQLite FTS5 / FTS4
                    Full-Text Search employs BM25 scoring based on term frequency and inverse document frequency (TF-IDF).
                    It excels at exact alphanumeric matches, serial numbers, code identifiers, and rare proper nouns.
                    
                    2. Vector Search via Dense Embeddings
                    Vector retrieval embeds queries and documents into continuous vector manifolds.
                    It captures synonyms, paraphrased concepts, and cross-lingual meaning that keyword matching misses completely.
                    
                    3. Reciprocal Rank Fusion (RRF)
                    RRF fuses disparate rank orders without requiring score calibration or threshold tuning:
                    Score = (1.0 / (60 + Rank_Vector)) + (1.0 / (60 + Rank_FTS))
                    Top documents appearing consistently in both ranks receive prominent score promotions.
                """.trimIndent()
            ),
            SampleDoc(
                fileName = "Distributed_Systems_and_Consensus.txt",
                tags = listOf("Distributed", "Engineering"),
                content = """
                    Distributed Consensus Protocols & Partition Tolerance
                    
                    The Raft consensus algorithm maintains a replicated state machine across independent cluster nodes.
                    Nodes transition between Follower, Candidate, and Leader states through randomized election timeouts.
                    
                    Key guarantees:
                    - Election Safety: At most one leader can be elected in a given term.
                    - Leader Append-Only: A leader never overwrites or truncates its log; it only appends new entries.
                    - Log Matching: If two logs contain an entry with the same index and term, then the logs are identical in all entries up through the given index.
                    
                    Under the CAP theorem, distributed databases partition network partitions (P) by either choosing
                    linearizable consistency (CP) or high availability (AP).
                """.trimIndent()
            ),
            SampleDoc(
                fileName = "Neural_Network_Transformers_Explained.html",
                tags = listOf("AI", "Research", "NLP"),
                content = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Transformer Attention Mechanisms</title></head>
                    <body>
                        <h1>Attention Is All You Need: Modern NLP Architectures</h1>
                        <p>The Scaled Dot-Product Attention function maps query, key, and value vectors according to:
                        <strong>Attention(Q, K, V) = softmax(Q * K^T / sqrt(d_k)) * V</strong></p>
                        <h2>Multi-Head Self-Attention</h2>
                        <p>Multi-Head Attention projects queries, keys, and values h times with learned parameter matrices.
                        This enables the model to simultaneously attend to information from different representation subspaces at different positions.</p>
                        <h3>Feed-Forward Networks & Residual Normalization</h3>
                        <p>Each sub-layer incorporates a residual skip-connection followed by Layer Normalization (LayerNorm),
                        preventing vanishing gradients across 12 to 96 stacked transformer blocks.</p>
                    </body>
                    </html>
                """.trimIndent()
            ),
            SampleDoc(
                fileName = "Quantum_Computing_Principles.md",
                tags = listOf("Physics", "Research"),
                content = """
                    # Quantum Information & Qubit Superposition
                    
                    Unlike classical bits which exist deterministically as 0 or 1, a quantum bit (qubit) exists in a linear superposition:
                    |psi> = alpha |0> + beta |1>, where |alpha|^2 + |beta|^2 = 1.
                    
                    ## Quantum Entanglement & Bell States
                    Entangled pairs exhibit non-local quantum correlations violating Bell's inequalities.
                    Measuring one qubit instantaneously projects the quantum state of its entangled counterpart.
                    
                    ## Quantum Algorithms
                    - Shor's Algorithm: Factors composite integers in polynomial time O((log N)^3) on quantum circuits.
                    - Grover's Algorithm: Provides quadratic speedup O(sqrt(N)) for unstructured database searches.
                    - Quantum Error Correction: Surface codes protect logical qubits against environmental decoherence.
                """.trimIndent()
            ),
            SampleDoc(
                fileName = "Android_Storage_Access_Framework_Guide.json",
                tags = listOf("Android", "Security"),
                content = """
                    {
                      "topic": "Storage Access Framework & Security",
                      "action": "ACTION_OPEN_DOCUMENT_TREE",
                      "persistence": "takePersistableUriPermission",
                      "guidelines": {
                        "zero_permission_design": "SAF allows users to grant scoped directory access without requesting broad READ_EXTERNAL_STORAGE permissions.",
                        "background_work": "Document URIs must be accessed through ContentResolver with persistent read permissions surviving device reboots.",
                        "workmanager_safety": "WorkManager CoroutineWorker with ForegroundInfo prevents Android OS from killing long-running document indexing pipelines."
                      }
                    }
                """.trimIndent()
            ),
            SampleDoc(
                fileName = "Medical_Immunology_and_mRNA_Vaccines.txt",
                tags = listOf("Science", "Biology"),
                content = """
                    Immunological Mechanisms of Nucleoside-Modified mRNA
                    
                    Messenger RNA (mRNA) vaccine platforms deliver synthetic genetic instructions encapsulated inside lipid nanoparticles (LNPs).
                    The LNPs protect delicate mRNA molecules from cellular ribonucleases and facilitate endosomal escape into the cytoplasm.
                    
                    Host ribosomes translate the mRNA into target viral antigen proteins (such as the prefusion-stabilized Spike glycoprotein).
                    Dendritic cells present these antigens via Major Histocompatibility Complex (MHC) Class I and Class II pathways,
                    stimulating robust CD4+ helper T-cells, CD8+ cytotoxic T-lymphocytes, and germinal center B-cells producing neutralizing antibodies.
                """.trimIndent()
            )
        )

        var totalChunks = 0
        val errors = mutableListOf<String>()

        sampleFiles.forEachIndexed { index, sample ->
            onProgress(index + 1, sampleFiles.size, sample.fileName, 10)
            val file = File(sampleDocsDir, sample.fileName)
            FileOutputStream(file).use { out ->
                out.write(sample.content.toByteArray(Charsets.UTF_8))
            }
            val docFile = DocumentFile.fromFile(file)
            val res = indexDocumentSafely(docFile) { _, cur, tot ->
                val subPercent = if (tot > 0) (cur * 100) / tot else 50
                onProgress(index + 1, sampleFiles.size, sample.fileName, subPercent)
            }
            if (res.isSuccess) {
                totalChunks += res.chunksIndexed
                if (sample.tags.isNotEmpty()) {
                    dao.setTagsForFile(docFile.uri.toString(), sample.tags)
                }
            } else {
                errors.add("${sample.fileName}: ${res.errorMessage}")
            }
        }

        Pair(totalChunks, errors)
    }

    /**
     * Seeds realistic sample SMS backup XML and WhatsApp chat history exports
     * into private app storage for testing private offline chat search.
     */
    suspend fun seedSampleChatBackup(): List<File> = withContext(Dispatchers.IO) {
        val chatDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "chat_backups").apply { mkdirs() }

        // 1. Android SMS Backup & Restore standard XML format
        val smsFile = File(chatDir, "sms_backup_2024.xml")
        val smsXmlContent = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <smses count="6">
              <sms protocol="0" address="+15550192834" date="1709280000000" type="1" subject="null" body="Hey! Did you finish reviewing the Q3 budget report and vector search benchmarks?" toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="0" sub_id="-1" readable_date="Mar 1, 2024 10:00:00 AM" contact_name="Sarah Miller" />
              <sms protocol="0" address="+15550192834" date="1709280120000" type="2" subject="null" body="Yes Sarah, LiteRT QNN delegate achieved 4.2ms latency on 384-dimensional embeddings!" toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="0" sub_id="-1" readable_date="Mar 1, 2024 10:02:00 AM" contact_name="Sarah Miller" />
              <sms protocol="0" address="+15550192834" date="1709280200000" type="1" subject="null" body="That is incredible! What about gaming performance while indexing in background?" toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="0" sub_id="-1" readable_date="Mar 1, 2024 10:03:20 AM" contact_name="Sarah Miller" />
              <sms protocol="0" address="+15550192834" date="1709280300000" type="2" subject="null" body="We built a Gaming Mode overlay that automatically suspends indexing to keep 120 FPS buttery smooth." toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="0" sub_id="-1" readable_date="Mar 1, 2024 10:05:00 AM" contact_name="Sarah Miller" />
              <sms protocol="0" address="+15550882319" date="1709366400000" type="1" subject="null" body="Dr. David here. Your annual health physical is confirmed for next Thursday at 2:30 PM." toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="0" sub_id="-1" readable_date="Mar 2, 2024 10:00:00 AM" contact_name="Dr. David Chen" />
              <sms protocol="0" address="+15550882319" date="1709366500000" type="2" subject="null" body="Thank you Dr. Chen, I have the lipid panel and vaccine paperwork ready." toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="0" sub_id="-1" readable_date="Mar 2, 2024 10:01:40 AM" contact_name="Dr. David Chen" />
            </smses>
        """.trimIndent()
        FileOutputStream(smsFile).use { it.write(smsXmlContent.toByteArray(Charsets.UTF_8)) }

        // 2. WhatsApp chat export format
        val whatsappFile = File(chatDir, "WhatsApp Chat with Alex Rivera.txt")
        val whatsappContent = """
            [03/05/2024, 14:15:22] Alex Rivera: Hey, remember the flight confirmation code for the Seattle conference?
            [03/05/2024, 14:16:05] Me: Yes! Confirmation is SEA-9824 with Alaska Airlines.
            [03/05/2024, 14:16:40] Alex Rivera: Perfect. Hotel reservation is at the Grand Hyatt downtown, booked under engineering team.
            [03/05/2024, 14:18:10] Me: Got it. I will bring the offline vector search presentation slides and demo devices.
            [03/05/2024, 14:19:00] Alex Rivera: Great, see you at the airport Monday morning!
        """.trimIndent()
        FileOutputStream(whatsappFile).use { it.write(whatsappContent.toByteArray(Charsets.UTF_8)) }

        listOf(smsFile, whatsappFile)
    }

    /**
     * Re-embeds all existing chunks in the local SQLite database using the active model.
     * Guarantees all indexed chunks take advantage of the selected model's superior accuracy.
     */
    suspend fun reindexAllDocumentsWithActiveModel(
        onProgress: (current: Int, total: Int, currentFile: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val allChunks = dao.getAllChunks()
        if (allChunks.isEmpty()) return@withContext 0

        val groupedByFile = allChunks.groupBy { it.fileUri }
        var updatedChunks = 0

        groupedByFile.entries.forEachIndexed { idx, entry ->
            val uri = entry.key
            val chunks = entry.value
            val fileName = chunks.firstOrNull()?.fileName ?: "Document"

            onProgress(idx + 1, groupedByFile.size, fileName)

            val texts = chunks.map { it.chunkText }
            val newEmbeddings = modelManager.embedBatch(texts, isQuery = false, batchSize = 8)

            val updatedEntities = chunks.mapIndexed { i, oldChunk ->
                val newEmb = if (i < newEmbeddings.size) newEmbeddings[i] else oldChunk.embedding
                val newBlob = com.example.engine.VectorSimilarityUtils.floatArrayToByteArray(newEmb)
                oldChunk.copy(embeddingBlob = newBlob)
            }

            dao.deleteFileRecord(uri)
            dao.insertChunksWithFts(updatedEntities)
            updatedChunks += updatedEntities.size
        }

        updatedChunks
    }

    private data class SampleDoc(
        val fileName: String,
        val content: String,
        val tags: List<String> = emptyList()
    )
}
