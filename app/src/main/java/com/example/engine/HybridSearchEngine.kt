package com.example.engine

import com.example.data.local.DocumentChunkDao
import com.example.data.local.DocumentChunkEntity
import com.example.engine.extraction.ChunkMetadata
import com.example.engine.search.MatchType
import com.example.engine.search.QueryParser
import com.example.engine.search.RankCandidate
import com.example.engine.search.SearchRanker
import com.example.engine.search.SemanticCalibration
import com.example.engine.search.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class SearchResult(
    val chunkId: Long,
    val fileUri: String,
    val fileName: String,
    val chunkIndex: Int,
    val chunkText: String,
    val snippet: String,
    val highlightedTerms: List<String>,
    val cosineSimilarity: Float,
    val vectorRank: Int?,
    val ftsRank: Int?,
    val rrfScore: Float,
    val bm25Score: Float = 0f,
    val combinedScore: Float = 0f,
    val latencyMs: Long,
    val tags: List<String> = emptyList(),
    val timestamp: Long = 0L,
    val fileSize: Long = 0L,
    /** Why this file was found (file name, exact phrase, text in image, similar meaning, ...). */
    val matchType: MatchType = MatchType.NONE,
    /** How many chunks of this file matched; the result shows the best one. */
    val matchCount: Int = 1,
    val matchedInName: Boolean = false,
    val matchedInText: Boolean = false,
    /** 0..1 word-match strength (file name or text) and calibrated meaning strength; see [SearchRanker]. */
    val lexicalScore: Float = 0f,
    val semanticScore: Float = 0f,
    /** "p. 3" for PDF chunks. */
    val pageLabel: String? = null
) {
    val fileExtension: String
        get() = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

class HybridSearchEngine(
    private val dao: DocumentChunkDao,
    private val embeddingEngine: OnDeviceEmbeddingEngine,
    val unifiedModelManager: com.example.engine.model.UnifiedEmbeddingManager? = null
) {
    companion object {
        private const val RRF_K = 60.0
        private const val SNIPPET_WINDOW = 220
        private const val MAX_FTS_CANDIDATES = 400
        private const val MAX_NAME_CANDIDATES = 150
        private const val MAX_SEMANTIC_CANDIDATES = 60
        /** Stays below SQLite's 999 bound-variable limit on older Android versions. */
        private const val SQL_BATCH = 500
        private const val SIMILAR_OVERSAMPLE = 6
        /** Below this cosine similarity a chunk is not offered as "similar". */
        private const val MIN_SIMILAR_SCORE = 0.3f
    }

    suspend fun search(
        query: String,
        mode: SearchMode = SearchMode.HYBRID,
        topK: Int = 30,
        filterTag: String? = null,
        fileTypeFilter: String? = null,
        sortOrder: SearchSortOrder = SearchSortOrder.RELEVANCE,
        semanticScoring: Boolean = true,
        semanticWeight: Float = 0.5f
    ): List<SearchResult> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            if (filterTag.isNullOrBlank() || filterTag.equals("All", ignoreCase = true)) {
                return@withContext emptyList()
            }
            // Return chunks matching the selected tag directly
            val matchingUris = dao.getFileUrisForTag(filterTag).toSet()
            val allChunks = dao.getAllChunks()
            val filtered = allChunks.filter { chunk ->
                matchingUris.contains(chunk.fileUri) ||
                        chunk.tags.split(",").map { it.trim() }.any { it.equals(filterTag, ignoreCase = true) }
            }
            val uniqueUris = filtered.map { it.fileUri }.distinct()
            val tagsByUri = HashMap<String, List<String>>()
            for (uri in uniqueUris) {
                tagsByUri[uri] = dao.getTagsForFile(uri)
            }
            val latency = System.currentTimeMillis() - startTime
            val rawResults = filtered.map { chunk ->
                val resolvedTags = tagsByUri[chunk.fileUri]?.takeIf { it.isNotEmpty() }
                    ?: if (chunk.tags.isNotBlank()) chunk.tags.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
                SearchResult(
                    chunkId = chunk.id,
                    fileUri = chunk.fileUri,
                    fileName = chunk.fileName,
                    chunkIndex = chunk.chunkIndex,
                    chunkText = chunk.chunkText,
                    snippet = chunk.chunkText.take(200) + if (chunk.chunkText.length > 200) "…" else "",
                    highlightedTerms = emptyList(),
                    cosineSimilarity = 1.0f,
                    vectorRank = null,
                    ftsRank = null,
                    rrfScore = 1.0f,
                    latencyMs = latency,
                    tags = resolvedTags,
                    timestamp = chunk.timestamp,
                    fileSize = chunk.chunkText.length.toLong()
                )
            }
            return@withContext SearchFilterState(selectedFileType = fileTypeFilter, sortOrder = sortOrder)
                .apply(rawResults)
                .take(topK)
        }

        // Parse the untrimmed text: a trailing space means the last word is finished (not a prefix).
        val parsed = QueryParser.parse(query.trimStart())
        if (!parsed.hasPositiveTerms) {
            return@withContext if (parsed.extensions.isNotEmpty()) {
                listFilesOfType(parsed.extensions, filterTag, topK, startTime)
            } else {
                emptyList()
            }
        }
        val terms = parsed.requiredTerms

        // 1. Word candidates: chunks containing every word first, then chunks containing any of them.
        // FTS returns matches in table order, so the all-words query keeps the best matches from being
        // pushed out by the limit when a word is common.
        val candidates = LinkedHashMap<Long, DocumentChunkEntity>()
        val ftsRankMap = HashMap<Long, Int>()
        fun addWordHits(chunks: List<DocumentChunkEntity>) {
            for (c in chunks) {
                if (candidates.putIfAbsent(c.id, c) == null) ftsRankMap[c.id] = ftsRankMap.size + 1
            }
        }
        addWordHits(runFts(terms.joinToString(" ") { "$it*" }))
        if (terms.size > 1) addWordHits(runFts(terms.joinToString(" OR ") { "$it*" }))
        if (candidates.isEmpty()) {
            for (term in terms.sortedByDescending { it.length }.take(3)) {
                addWordHits(dao.searchFallbackLike(term))
            }
        }

        // 2. File-name candidates: every indexed file name is checked (with typo tolerance), so a file
        // is found by its name even if its text never mentions the words.
        val nameHits = dao.getFileNameEntries()
            .asSequence()
            .map { it to SearchRanker.nameScore(it.fileName, parsed) }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(MAX_NAME_CANDIDATES)
            .map { it.first.id }
            .toList()
        fetchMissing(nameHits, candidates)

        // 3. Meaning candidates: cosine similarity of the query vector against every stored chunk vector.
        val scanVectors = mode != SearchMode.KEYWORD || semanticScoring
        val cosineById = HashMap<Long, Float>()
        val vectorRankMap = HashMap<Long, Int>()
        var calibration = SemanticCalibration.NONE
        var corpusSize = 0
        if (scanVectors) {
            val queryEmbedding = if (unifiedModelManager != null) {
                unifiedModelManager.embedText(cleanQuery, isQuery = true)
            } else {
                embeddingEngine.embedText(cleanQuery)
            }
            // Only chunks embedded by the model that produced the query vector are comparable; the rest are
            // still found by their words until they are re-indexed. A bare engine (no model manager) has no
            // model tags to enforce, so everything is compared.
            val queryModelId: String? = unifiedModelManager?.effectiveModelId()
            val rows = dao.getAllEmbeddingRows()
            corpusSize = rows.size
            val sims = FloatArray(rows.size)
            var n = 0
            for (row in rows) {
                if (queryModelId != null && ChunkMetadata.model(row.metadata) != queryModelId) continue
                if (queryEmbedding.isEmpty() || TensorFlowLiteQuantizer.blobDimension(row.embeddingBlob) != queryEmbedding.size) continue
                val sim = VectorSimilarityUtils.cosineSimilarityWithEmbeddingBlob(queryEmbedding, row.embeddingBlob)
                cosineById[row.id] = sim
                sims[n++] = sim
            }
            calibration = SemanticCalibration.from(sims.copyOf(n))
            val byCosine = cosineById.entries.sortedByDescending { it.value }
            byCosine.forEachIndexed { i, e -> vectorRankMap[e.key] = i + 1 }
            val wanted = if (mode == SearchMode.VECTOR) topK * 2 else MAX_SEMANTIC_CANDIDATES
            fetchMissing(byCosine.take(wanted).map { it.key }, candidates)
        }
        if (corpusSize == 0) corpusSize = dao.getTotalChunksCountDirect()
        if (corpusSize == 0 && candidates.isEmpty()) return@withContext emptyList()

        // 4. Label filter
        val filtered = if (!filterTag.isNullOrBlank() && !filterTag.equals("All", ignoreCase = true)) {
            val matchingUris = dao.getFileUrisForTag(filterTag).toSet()
            candidates.values.filter { chunk ->
                matchingUris.contains(chunk.fileUri) ||
                        chunk.tags.split(",").map { it.trim() }.any { it.equals(filterTag, ignoreCase = true) }
            }
        } else {
            candidates.values.toList()
        }

        // 5. Rank: name, words, phrase, meaning; one result per file.
        val ranked = SearchRanker.rank(
            filtered.map {
                RankCandidate(it.id, it.fileUri, it.fileName, it.chunkIndex, it.chunkText, it.tags, it.timestamp, cosineById[it.id])
            },
            parsed,
            calibration,
            mode,
            semanticWeight,
            corpusSize
        )

        val latency = System.currentTimeMillis() - startTime
        val highlight = parsed.allTerms
        val results = ranked.take(topK * 2).map { rf ->
            val chunk = candidates.getValue(rf.best.id)
            val vRank = vectorRankMap[chunk.id]
            val fRank = ftsRankMap[chunk.id]
            val rrf = (if (vRank != null) 1.0 / (RRF_K + vRank) else 0.0) + (if (fRank != null) 1.0 / (RRF_K + fRank) else 0.0)
            SearchResult(
                chunkId = chunk.id,
                fileUri = chunk.fileUri,
                fileName = chunk.fileName,
                chunkIndex = chunk.chunkIndex,
                chunkText = chunk.chunkText,
                snippet = SearchRanker.bestSnippet(chunk.chunkText, if (rf.textScore > 0f) highlight else emptyList(), SNIPPET_WINDOW),
                highlightedTerms = highlight,
                cosineSimilarity = (cosineById[chunk.id] ?: 0f).coerceIn(0f, 1f),
                vectorRank = vRank,
                ftsRank = fRank,
                rrfScore = rrf.toFloat(),
                bm25Score = rf.textScore,
                combinedScore = rf.score,
                latencyMs = latency,
                tags = resolveTags(chunk),
                timestamp = chunk.timestamp,
                fileSize = chunk.chunkText.length.toLong(),
                matchType = rf.matchType,
                matchCount = rf.matchCount,
                matchedInName = rf.matchedInName,
                matchedInText = rf.matchedInText,
                lexicalScore = rf.lexicalScore,
                semanticScore = rf.semanticScore,
                pageLabel = ChunkMetadata.pageLabel(chunk.metadata)
            )
        }

        return@withContext SearchFilterState(selectedFileType = fileTypeFilter, sortOrder = sortOrder)
            .apply(results)
            .take(topK)
    }

    /** `ext:pdf` with no words: every file of that type, newest first. */
    private suspend fun listFilesOfType(
        extensions: Set<String>,
        filterTag: String?,
        topK: Int,
        startTime: Long
    ): List<SearchResult> {
        val tagUris = if (!filterTag.isNullOrBlank() && !filterTag.equals("All", ignoreCase = true)) {
            dao.getFileUrisForTag(filterTag).toSet()
        } else null
        val entries = dao.getFileNameEntries()
            .filter { TextMatch.extension(it.fileName) in extensions && (tagUris == null || it.fileUri in tagUris) }
            .sortedByDescending { it.timestamp }
            .take(topK)
        val chunks = HashMap<Long, DocumentChunkEntity>()
        fetchMissing(entries.map { it.id }, chunks)
        val latency = System.currentTimeMillis() - startTime
        return entries.mapNotNull { e -> chunks[e.id] }.map { chunk ->
            SearchResult(
                chunkId = chunk.id,
                fileUri = chunk.fileUri,
                fileName = chunk.fileName,
                chunkIndex = chunk.chunkIndex,
                chunkText = chunk.chunkText,
                snippet = SearchRanker.bestSnippet(chunk.chunkText, emptyList(), SNIPPET_WINDOW),
                highlightedTerms = emptyList(),
                cosineSimilarity = 0f,
                vectorRank = null,
                ftsRank = null,
                rrfScore = 0f,
                latencyMs = latency,
                tags = resolveTags(chunk),
                timestamp = chunk.timestamp,
                fileSize = chunk.chunkText.length.toLong(),
                pageLabel = ChunkMetadata.pageLabel(chunk.metadata)
            )
        }
    }

    /**
     * Documents most similar to the chunk [chunkId]: its stored vector is compared with every other chunk embedded by
     * the same model, and the best chunk of each other file is returned, best first. Nothing is embedded, so this
     * is as cheap as a normal vector scan. Returns nothing for chunks without a model-tagged vector.
     */
    suspend fun findSimilar(chunkId: Long, limit: Int = 8): List<SearchResult> = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val source = dao.getChunksByIds(listOf(chunkId)).firstOrNull() ?: return@withContext emptyList()
        val sourceModel = ChunkMetadata.model(source.metadata) ?: return@withContext emptyList()
        val query = VectorSimilarityUtils.byteArrayToFloatArray(source.embeddingBlob)
        if (query.isEmpty() || limit <= 0) return@withContext emptyList()

        // Several chunks of the same file will rank high, so look well past `limit` before grouping by file.
        val scan = VectorScanner.scan(
            query = query,
            modelId = sourceModel,
            topK = limit * SIMILAR_OVERSAMPLE + SIMILAR_OVERSAMPLE,
            expectedCount = dao.getTotalChunksCountDirect()
        ) { afterId, pageLimit -> dao.getEmbeddingPage(afterId, pageLimit) }

        val simById = HashMap<Long, Float>()
        for (i in scan.topIds.indices) simById[scan.topIds[i]] = scan.topSims[i]

        val bestPerFile = LinkedHashMap<String, DocumentChunkEntity>()
        for (chunk in loadChunksByIds(scan.topIds.toList())) {
            if (chunk.fileUri == source.fileUri) continue
            if ((simById[chunk.id] ?: 0f) < MIN_SIMILAR_SCORE) continue
            if (bestPerFile.size >= limit && !bestPerFile.containsKey(chunk.fileUri)) break
            bestPerFile.putIfAbsent(chunk.fileUri, chunk) // chunks arrive best first, so the first one per file wins
        }

        val latency = System.currentTimeMillis() - start
        bestPerFile.values.take(limit).map { chunk ->
            val sim = simById[chunk.id] ?: 0f
            SearchResult(
                chunkId = chunk.id,
                fileUri = chunk.fileUri,
                fileName = chunk.fileName,
                chunkIndex = chunk.chunkIndex,
                chunkText = chunk.chunkText,
                snippet = chunk.chunkText.take(SNIPPET_WINDOW) + if (chunk.chunkText.length > SNIPPET_WINDOW) "…" else "",
                highlightedTerms = emptyList(),
                cosineSimilarity = sim,
                vectorRank = scan.rankOf(chunk.id, sim),
                ftsRank = null,
                rrfScore = sim,
                combinedScore = sim,
                latencyMs = latency,
                tags = resolveTags(chunk),
                timestamp = chunk.timestamp,
                fileSize = chunk.chunkText.length.toLong(),
                pageLabel = ChunkMetadata.pageLabel(chunk.metadata)
            )
        }
    }

    private suspend fun loadChunksByIds(ids: List<Long>): List<DocumentChunkEntity> {
        val byId = HashMap<Long, DocumentChunkEntity>(ids.size)
        for (batch in ids.chunked(SQL_BATCH)) {
            for (chunk in dao.getChunksByIds(batch)) byId[chunk.id] = chunk
        }
        // Keep the vector ranking order.
        return ids.mapNotNull { byId[it] }
    }

    private suspend fun resolveTags(chunk: DocumentChunkEntity): List<String> =
        dao.getTagsForFile(chunk.fileUri).takeIf { it.isNotEmpty() }
            ?: chunk.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }

    /** Loads the chunks in [ids] that aren't in [into] yet; batched to stay under SQLite's variable limit. */
    private suspend fun fetchMissing(ids: List<Long>, into: MutableMap<Long, DocumentChunkEntity>) {
        val missing = ids.filter { it !in into }
        for (batch in missing.chunked(500)) {
            for (c in dao.getChunksByIds(batch)) into.putIfAbsent(c.id, c)
        }
    }

    private suspend fun runFts(ftsQuery: String): List<DocumentChunkEntity> =
        try {
            dao.searchFts(ftsQuery, MAX_FTS_CANDIDATES)
        } catch (_: Exception) {
            emptyList()
        }
}
