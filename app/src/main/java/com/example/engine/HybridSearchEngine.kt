package com.example.engine

import com.example.data.local.DocumentChunkDao
import com.example.data.local.DocumentChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class SearchMode(val displayName: String) {
    HYBRID("Hybrid (RRF)"),
    VECTOR("Vector (KNN)"),
    KEYWORD("Keyword (FTS)")
}

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
    val fileSize: Long = 0L
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
        /** Stays below SQLite's 999 bound-variable limit on older Android versions. */
        private const val SQL_BATCH = 500
        /** How many keyword matches are fetched before BM25 ordering picks the best. */
        private const val FTS_CANDIDATE_LIMIT = 400
        private val WHITESPACE = Regex("""\s+""")
        private val NON_WORD = Regex("""[^\w\s]""")
    }

    suspend fun search(
        query: String,
        mode: SearchMode = SearchMode.HYBRID,
        topK: Int = 30,
        filterTag: String? = null,
        fileTypeFilter: String? = null,
        sortOrder: SearchSortOrder = SearchSortOrder.RELEVANCE,
        semanticScoring: Boolean = true
    ): List<SearchResult> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val cleanQuery = query.trim()
        val tagActive = !filterTag.isNullOrBlank() && !filterTag.equals("All", ignoreCase = true)
        if (cleanQuery.isEmpty()) {
            if (!tagActive) return@withContext emptyList()
            // No query: list the chunks carrying the selected tag.
            val filtered = chunksWithTag(filterTag!!)
            val tagsByUri = loadTags(filtered.map { it.fileUri }.distinct())
            val latency = System.currentTimeMillis() - startTime
            val rawResults = filtered.map { chunk ->
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
                    tags = resolveTags(chunk, tagsByUri),
                    timestamp = chunk.timestamp,
                    fileSize = chunk.chunkText.length.toLong()
                )
            }
            return@withContext SearchFilterState(selectedFileType = fileTypeFilter, sortOrder = sortOrder)
                .apply(rawResults)
                .take(topK)
        }

        val corpusSize = dao.getTotalChunksCountDirect()
        if (corpusSize == 0) return@withContext emptyList()

        // 1. FTS keyword pipeline (runs first so the vector scan knows which keyword hits it must score).
        val queryTokens = extractQueryKeywords(cleanQuery)
        val ftsResults = executeFtsQuery(cleanQuery, queryTokens, corpusSize)
        val ftsRankMap = HashMap<Long, Int>(ftsResults.size)
        for (i in ftsResults.indices) {
            ftsRankMap[ftsResults[i].id] = i + 1
        }

        // 2. Vector pipeline: stream the embeddings in small pages and keep only the best matches (plus the
        // similarity of every keyword hit), rather than loading each chunk's text and vector into memory.
        // KEYWORD callers can opt out of the scan (semanticScoring = false) and re-rank the FTS hits themselves.
        val scanVectors = mode != SearchMode.KEYWORD || semanticScoring
        val vectorKeep = when (mode) {
            SearchMode.VECTOR -> topK
            SearchMode.HYBRID -> topK * 2
            SearchMode.KEYWORD -> 0
        }
        val scan: VectorScanResult? = if (scanVectors) {
            val queryEmbedding = if (unifiedModelManager != null) {
                unifiedModelManager.embedText(cleanQuery, isQuery = true)
            } else {
                embeddingEngine.embedText(cleanQuery)
            }
            // Only chunks embedded by the model that produced the query vector are comparable; the rest score 0
            // and are still found by keyword search until they are re-indexed. Without a model manager (a bare
            // engine) there are no model tags to enforce, so everything is compared.
            VectorScanner.scan(
                query = queryEmbedding,
                modelId = unifiedModelManager?.effectiveModelId(),
                topK = vectorKeep,
                wantedIds = ftsRankMap.keys,
                expectedCount = corpusSize
            ) { afterId, limit -> dao.getEmbeddingPage(afterId, limit) }
        } else {
            null
        }
        val simById = HashMap<Long, Float>()
        if (scan != null) {
            for (i in scan.topIds.indices) simById[scan.topIds[i]] = scan.topSims[i]
            simById.putAll(scan.wantedSims)
        }

        // 3. Candidate selection based on mode
        val candidateChunks = LinkedHashMap<Long, DocumentChunkEntity>()
        val vectorIds = if (mode != SearchMode.KEYWORD && scan != null) scan.topIds.toList() else emptyList()
        if (vectorIds.isNotEmpty()) {
            for (chunk in loadChunksByIds(vectorIds)) candidateChunks[chunk.id] = chunk
        }
        val ftsTake = when (mode) {
            SearchMode.VECTOR -> 0
            SearchMode.KEYWORD -> topK
            SearchMode.HYBRID -> topK * 2
        }
        for (item in ftsResults.take(ftsTake)) {
            candidateChunks[item.id] = item
        }
        val rawCandidates = candidateChunks.values.toList()

        // Filter by tag if requested
        val filteredCandidates = if (tagActive) {
            val tag = filterTag!!
            val matchingUris = dao.getFileUrisForTag(tag).toSet()
            rawCandidates.filter { chunk -> matchingUris.contains(chunk.fileUri) || chunkTextHasTag(chunk, tag) }
        } else {
            rawCandidates
        }

        val tagsByUri = loadTags(filteredCandidates.map { it.fileUri }.distinct())

        // Calculate BM25 relevance scores for candidate chunks
        val bm25ScoresMap = calculateBm25Scores(filteredCandidates, queryTokens, corpusSize)
        val latency = System.currentTimeMillis() - startTime

        val results = filteredCandidates.map { chunk ->
            val cosineSim = simById[chunk.id] ?: 0f
            val vRank = scan?.takeIf { simById.containsKey(chunk.id) }?.rankOf(chunk.id, cosineSim)
            val fRank = ftsRankMap[chunk.id]
            val bm25Score = bm25ScoresMap[chunk.id] ?: 0f

            val rrfScore = when (mode) {
                SearchMode.HYBRID -> {
                    val vScore = if (vRank != null) 1.0 / (RRF_K + vRank) else 0.0
                    val fScore = if (fRank != null) 1.0 / (RRF_K + fRank) else 0.0
                    (vScore + fScore).toFloat()
                }
                SearchMode.VECTOR -> cosineSim
                SearchMode.KEYWORD -> if (fRank != null) (1.0 / (RRF_K + fRank)).toFloat() else bm25Score
            }

            val combinedScore = when (mode) {
                SearchMode.HYBRID -> (0.5f * cosineSim + 0.5f * bm25Score).coerceIn(0f, 1f)
                SearchMode.VECTOR -> cosineSim
                SearchMode.KEYWORD -> bm25Score
            }

            SearchResult(
                chunkId = chunk.id,
                fileUri = chunk.fileUri,
                fileName = chunk.fileName,
                chunkIndex = chunk.chunkIndex,
                chunkText = chunk.chunkText,
                snippet = generateHighlightedSnippet(chunk.chunkText, queryTokens),
                highlightedTerms = queryTokens,
                cosineSimilarity = cosineSim,
                vectorRank = vRank,
                ftsRank = fRank,
                rrfScore = rrfScore,
                bm25Score = bm25Score,
                combinedScore = combinedScore,
                latencyMs = latency,
                tags = resolveTags(chunk, tagsByUri),
                timestamp = chunk.timestamp,
                fileSize = chunk.chunkText.length.toLong()
            )
        }

        return@withContext SearchFilterState(selectedFileType = fileTypeFilter, sortOrder = sortOrder)
            .apply(results)
            .take(topK)
    }

    /** Chunks of every file tagged [tag], plus chunks whose own `tags` column mentions it, in id order. */
    private suspend fun chunksWithTag(tag: String): List<DocumentChunkEntity> {
        val byId = HashMap<Long, DocumentChunkEntity>()
        for (batch in dao.getFileUrisForTag(tag).chunked(SQL_BATCH)) {
            for (chunk in dao.getChunksForFiles(batch)) byId[chunk.id] = chunk
        }
        for (chunk in dao.getChunksWithTagText(tag)) {
            if (chunkTextHasTag(chunk, tag)) byId[chunk.id] = chunk
        }
        return byId.values.sortedBy { it.id }
    }

    private fun chunkTextHasTag(chunk: DocumentChunkEntity, tag: String): Boolean =
        chunk.tags.split(",").map { it.trim() }.any { it.equals(tag, ignoreCase = true) }

    private suspend fun loadChunksByIds(ids: List<Long>): List<DocumentChunkEntity> {
        val byId = HashMap<Long, DocumentChunkEntity>(ids.size)
        for (batch in ids.chunked(SQL_BATCH)) {
            for (chunk in dao.getChunksByIds(batch)) byId[chunk.id] = chunk
        }
        // Keep the vector ranking order.
        return ids.mapNotNull { byId[it] }
    }

    /** Tags of many files in as few queries as possible (instead of one query per file). */
    private suspend fun loadTags(fileUris: List<String>): Map<String, List<String>> {
        if (fileUris.isEmpty()) return emptyMap()
        val tags = HashMap<String, MutableList<String>>()
        for (batch in fileUris.chunked(SQL_BATCH)) {
            for (row in dao.getTagsForFiles(batch)) tags.getOrPut(row.fileUri) { ArrayList() }.add(row.tag)
        }
        return tags
    }

    private fun resolveTags(chunk: DocumentChunkEntity, tagsByUri: Map<String, List<String>>): List<String> =
        tagsByUri[chunk.fileUri]?.takeIf { it.isNotEmpty() }
            ?: if (chunk.tags.isNotBlank()) chunk.tags.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()

    /**
     * Calculates SQLite FTS BM25 relevance scores for candidate document chunks given a search query.
     * Implements standard Okapi BM25 formula over term frequency (tf), inverse document frequency (idf),
     * and document length normalization, with filename and tag match bonuses.
     */
    fun calculateBm25Scores(
        chunks: List<DocumentChunkEntity>,
        queryTokens: List<String>,
        totalCorpusSize: Int
    ): Map<Long, Float> {
        if (chunks.isEmpty() || queryTokens.isEmpty()) return emptyMap()

        val k1 = 1.2f
        val b = 0.75f
        val corpusSize = max(totalCorpusSize, chunks.size)

        // Calculate document lengths (in words) and average document length across candidate set
        val docLengths = HashMap<Long, Int>(chunks.size)
        var totalLength = 0L
        for (chunk in chunks) {
            val words = chunk.chunkText.split(WHITESPACE).count { it.isNotBlank() }
            val len = max(words, 1)
            docLengths[chunk.id] = len
            totalLength += len
        }
        val avgdl = max(1.0f, totalLength.toFloat() / chunks.size.toFloat())

        // Calculate document frequency df(t) for each query term in candidate set
        val docFreqs = HashMap<String, Int>()
        for (token in queryTokens) {
            var df = 0
            for (chunk in chunks) {
                if (chunk.chunkText.contains(token, ignoreCase = true) ||
                    chunk.fileName.contains(token, ignoreCase = true) ||
                    chunk.tags.contains(token, ignoreCase = true)) {
                    df++
                }
            }
            docFreqs[token] = df
        }

        // Calculate IDF for each query term: ln(1 + (N - df + 0.5)/(df + 0.5))
        val idfMap = HashMap<String, Float>()
        for ((token, df) in docFreqs) {
            val idf = kotlin.math.ln(1.0f + (corpusSize - df + 0.5f) / (df + 0.5f)).toFloat()
            idfMap[token] = max(0.1f, idf)
        }

        // Compute BM25 raw score for each chunk
        val rawBm25Map = HashMap<Long, Float>(chunks.size)
        var maxRaw = 0f
        var minRaw = Float.MAX_VALUE

        for (chunk in chunks) {
            val docLen = (docLengths[chunk.id] ?: 100).toFloat()
            val chunkTextLower = chunk.chunkText.lowercase(Locale.ROOT)
            val fileNameLower = chunk.fileName.lowercase(Locale.ROOT)
            val tagsLower = chunk.tags.lowercase(Locale.ROOT)

            var score = 0f
            for (token in queryTokens) {
                val idf = idfMap[token] ?: 0.5f

                // Count term frequency in text
                var tf = 0
                var idx = chunkTextLower.indexOf(token)
                while (idx != -1) {
                    tf++
                    idx = chunkTextLower.indexOf(token, idx + token.length)
                }

                // Title and tag match boost
                if (fileNameLower.contains(token)) tf += 2
                if (tagsLower.contains(token)) tf += 1

                if (tf > 0) {
                    val tfScore = (tf * (k1 + 1f)) / (tf + k1 * (1f - b + b * (docLen / avgdl)))
                    score += idf * tfScore
                }
            }

            rawBm25Map[chunk.id] = score
            if (score > maxRaw) maxRaw = score
            if (score < minRaw) minRaw = score
        }

        // Normalize raw BM25 scores to [0.0, 1.0]
        val normalizedMap = HashMap<Long, Float>(chunks.size)
        val range = maxRaw - minRaw
        for ((chunkId, rawScore) in rawBm25Map) {
            val norm = if (range > 1e-5f) {
                ((rawScore - minRaw) / range).coerceIn(0f, 1f)
            } else if (rawScore > 0f) {
                0.8f
            } else {
                0.0f
            }
            normalizedMap[chunkId] = norm
        }

        return normalizedMap
    }

    /**
     * Keyword candidates, best first. FTS4 returns matches in row order, so a wide candidate window is fetched
     * and ordered by BM25 here; otherwise `ftsRank` would only reflect insertion order and the best matches could
     * be cut off by the row limit before ever being ranked.
     */
    private suspend fun executeFtsQuery(query: String, queryTokens: List<String>, corpusSize: Int): List<DocumentChunkEntity> {
        val sanitized = sanitizeFtsQuery(query)
        val candidates = if (sanitized.isBlank()) {
            dao.searchFallbackLike(query, FTS_CANDIDATE_LIMIT)
        } else {
            try {
                dao.searchFts(sanitized, FTS_CANDIDATE_LIMIT).ifEmpty { dao.searchFallbackLike(query, FTS_CANDIDATE_LIMIT) }
            } catch (_: Exception) {
                dao.searchFallbackLike(query, FTS_CANDIDATE_LIMIT)
            }
        }
        if (candidates.size < 2 || queryTokens.isEmpty()) return candidates
        val scores = calculateBm25Scores(candidates, queryTokens, corpusSize)
        // sortedByDescending is stable, so equal scores keep their original (row) order.
        return candidates.sortedByDescending { scores[it.id] ?: 0f }
    }

    private fun sanitizeFtsQuery(raw: String): String {
        val words = raw.trim()
            .replace(NON_WORD, " ")
            .split(WHITESPACE)
            .filter { it.length >= 2 }
        if (words.isEmpty()) return ""
        // FTS syntax: "word1"* OR "word2"*
        return words.joinToString(" OR ") { "$it*" }
    }

    private fun extractQueryKeywords(query: String): List<String> {
        return query.lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .split(WHITESPACE)
            .filter { it.length >= 2 }
    }

    private fun generateHighlightedSnippet(fullText: String, keywords: List<String>): String {
        if (fullText.length <= SNIPPET_WINDOW) return fullText

        var bestIndex = -1
        val lowerText = fullText.lowercase(Locale.ROOT)

        for (kw in keywords) {
            val idx = lowerText.indexOf(kw)
            if (idx != -1) {
                bestIndex = idx
                break
            }
        }

        if (bestIndex == -1) {
            // No direct keyword match; return beginning of the chunk
            return fullText.take(SNIPPET_WINDOW) + "…"
        }

        val start = max(0, bestIndex - 60)
        val end = min(fullText.length, bestIndex + SNIPPET_WINDOW - 60)

        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < fullText.length) "…" else ""

        return prefix + fullText.substring(start, end).trim() + suffix
    }
}
