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

        // 1. Vector Search Pipeline
        // KEYWORD callers can opt out of the full-corpus vector scan (semanticScoring = false) and instead
        // re-rank just the FTS candidates by cosine similarity themselves (see MainViewModel).
        val scanVectors = mode != SearchMode.KEYWORD || semanticScoring
        val queryEmbedding = when {
            !scanVectors -> FloatArray(0)
            unifiedModelManager != null -> unifiedModelManager.embedText(cleanQuery, isQuery = true)
            else -> embeddingEngine.embedText(cleanQuery)
        }
        val allChunks = if (scanVectors) dao.getAllChunks() else emptyList()
        val corpusSize = if (scanVectors) allChunks.size else dao.getTotalChunksCountDirect()
        if (corpusSize == 0) return@withContext emptyList()

        // Only chunks embedded by the model that produced the query vector are comparable; the rest score 0
        // here and are still found by keyword search until they are re-indexed.
        // Without a model manager (a bare engine) there are no model tags to enforce, so everything is compared.
        val queryModelId: String? = unifiedModelManager?.effectiveModelId()

        val vectorRanked = ArrayList<Pair<DocumentChunkEntity, Float>>(allChunks.size)
        for (chunk in allChunks) {
            val sameModel = queryModelId == null || com.example.engine.extraction.ChunkMetadata.model(chunk.metadata) == queryModelId
            val sim = if (sameModel) {
                val chunkVec = VectorSimilarityUtils.byteArrayToFloatArray(chunk.embeddingBlob)
                if (chunkVec.isNotEmpty() && chunkVec.size == queryEmbedding.size) {
                    VectorSimilarityUtils.calculateCosineSimilarity(queryEmbedding, chunkVec)
                } else {
                    0f
                }
            } else {
                0f
            }
            vectorRanked.add(Pair(chunk, sim))
        }
        vectorRanked.sortByDescending { it.second }

        // Vector rank lookup map (chunkId -> rank 1..N)
        val vectorRankMap = HashMap<Long, Int>(vectorRanked.size)
        val vectorSimMap = HashMap<Long, Float>(vectorRanked.size)
        for (i in vectorRanked.indices) {
            val (c, sim) = vectorRanked[i]
            vectorRankMap[c.id] = i + 1
            vectorSimMap[c.id] = sim
        }

        // 2. FTS Keyword Pipeline
        val ftsResults = executeFtsQuery(cleanQuery)
        val ftsRankMap = HashMap<Long, Int>(ftsResults.size)
        for (i in ftsResults.indices) {
            ftsRankMap[ftsResults[i].id] = i + 1
        }

        // 3. Score calculation based on mode
        val queryTokens = extractQueryKeywords(cleanQuery)
        val latency = System.currentTimeMillis() - startTime

        val candidateChunks = HashMap<Long, DocumentChunkEntity>()
        when (mode) {
            SearchMode.VECTOR -> {
                for (item in vectorRanked.take(topK)) {
                    candidateChunks[item.first.id] = item.first
                }
            }
            SearchMode.KEYWORD -> {
                for (item in ftsResults.take(topK)) {
                    candidateChunks[item.id] = item
                }
            }
            SearchMode.HYBRID -> {
                // Union candidates from Top-K vector and Top-K FTS
                for (item in vectorRanked.take(topK * 2)) {
                    candidateChunks[item.first.id] = item.first
                }
                for (item in ftsResults.take(topK * 2)) {
                    candidateChunks[item.id] = item
                }
            }
        }

        val rawCandidates = candidateChunks.values.toList()

        // Filter by tag if requested
        val filteredCandidates = if (!filterTag.isNullOrBlank() && !filterTag.equals("All", ignoreCase = true)) {
            val matchingUris = dao.getFileUrisForTag(filterTag).toSet()
            rawCandidates.filter { chunk ->
                matchingUris.contains(chunk.fileUri) ||
                        chunk.tags.split(",").map { it.trim() }.any { it.equals(filterTag, ignoreCase = true) }
            }
        } else {
            rawCandidates
        }

        // Preload tags map for unique candidate fileUris
        val uniqueUris = filteredCandidates.map { it.fileUri }.distinct()
        val tagsByUri = HashMap<String, List<String>>()
        for (uri in uniqueUris) {
            val dbTags = dao.getTagsForFile(uri)
            tagsByUri[uri] = dbTags
        }

        // Calculate BM25 relevance scores for candidate chunks
        val bm25ScoresMap = calculateBm25Scores(filteredCandidates, queryTokens, corpusSize)

        val results = filteredCandidates.map { chunk ->
            val vRank = vectorRankMap[chunk.id]
            val fRank = ftsRankMap[chunk.id]
            val cosineSim = vectorSimMap[chunk.id] ?: 0f
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

            val snippet = generateHighlightedSnippet(chunk.chunkText, queryTokens)

            // Resolve tags for this chunk's document
            val resolvedTags = tagsByUri[chunk.fileUri]?.takeIf { it.isNotEmpty() }
                ?: if (chunk.tags.isNotBlank()) chunk.tags.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()

            SearchResult(
                chunkId = chunk.id,
                fileUri = chunk.fileUri,
                fileName = chunk.fileName,
                chunkIndex = chunk.chunkIndex,
                chunkText = chunk.chunkText,
                snippet = snippet,
                highlightedTerms = queryTokens,
                cosineSimilarity = cosineSim,
                vectorRank = vRank,
                ftsRank = fRank,
                rrfScore = rrfScore,
                bm25Score = bm25Score,
                combinedScore = combinedScore,
                latencyMs = latency,
                tags = resolvedTags,
                timestamp = chunk.timestamp,
                fileSize = chunk.chunkText.length.toLong()
            )
        }

        return@withContext SearchFilterState(selectedFileType = fileTypeFilter, sortOrder = sortOrder)
            .apply(results)
            .take(topK)
    }

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
            val words = chunk.chunkText.split(Regex("""\s+""")).filter { it.isNotBlank() }.size
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

    private suspend fun executeFtsQuery(query: String): List<DocumentChunkEntity> {
        val sanitized = sanitizeFtsQuery(query)
        if (sanitized.isBlank()) {
            return dao.searchFallbackLike(query)
        }
        return try {
            val ftsList = dao.searchFts(sanitized)
            if (ftsList.isEmpty()) {
                dao.searchFallbackLike(query)
            } else {
                ftsList
            }
        } catch (_: Exception) {
            dao.searchFallbackLike(query)
        }
    }

    private fun sanitizeFtsQuery(raw: String): String {
        val words = raw.trim()
            .replace(Regex("""[^\w\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }
        if (words.isEmpty()) return ""
        // FTS syntax: "word1"* OR "word2"*
        return words.joinToString(" OR ") { "$it*" }
    }

    private fun extractQueryKeywords(query: String): List<String> {
        return query.lowercase(Locale.ROOT)
            .replace(Regex("""[^\w\s]"""), " ")
            .split(Regex("""\s+"""))
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
