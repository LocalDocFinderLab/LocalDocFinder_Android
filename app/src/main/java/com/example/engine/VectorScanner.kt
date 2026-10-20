package com.example.engine

import com.example.data.local.ChunkEmbeddingRow
import com.example.engine.extraction.ChunkMetadata
import java.util.PriorityQueue

/**
 * Outcome of one streaming vector scan over the whole corpus.
 *
 * Only ids and similarities are kept (4 bytes per chunk plus the top-K), never chunk text or vector blobs, so memory
 * stays flat however large the index grows.
 */
class VectorScanResult internal constructor(
    /** Chunk ids of the best matches, best first (ties broken by lower id, i.e. insertion order). */
    val topIds: LongArray,
    /** Similarity of each entry of [topIds]. */
    val topSims: FloatArray,
    /** Similarity of every chunk that was asked about through `wantedIds`, including ones outside the top-K. */
    val wantedSims: Map<Long, Float>,
    private val sortedSims: FloatArray,
    val scanned: Int
) {
    /** 1-based rank of [id] among the top-K, or by similarity for any other id that was scanned. */
    fun rankOf(id: Long, sim: Float): Int {
        val inTop = topIds.indexOf(id)
        if (inTop >= 0) return inTop + 1
        // First index whose value is > sim in the ascending array == number of chunks that are <= sim.
        var lo = 0
        var hi = sortedSims.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedSims[mid] <= sim) lo = mid + 1 else hi = mid
        }
        return (sortedSims.size - lo) + 1
    }
}

/**
 * Streams embeddings page by page (keyset pagination on `id`), scores them against the query vector in the
 * quantised domain and keeps only a bounded top-K, instead of loading every chunk of the database for each query.
 */
object VectorScanner {

    const val DEFAULT_PAGE_SIZE = 400

    /**
     * @param query L2-normalised query embedding.
     * @param modelId Only chunks embedded by this model are comparable; others score 0. `null` compares everything.
     * @param topK How many best chunks to retain.
     * @param wantedIds Chunks whose similarity is needed even when they are not in the top-K (e.g. keyword hits).
     * @param expectedCount Rough corpus size, used only to pre-size the similarity buffer.
     * @param nextPage Returns up to `limit` rows with `id > afterId`, ordered by id ascending.
     */
    suspend fun scan(
        query: FloatArray,
        modelId: String?,
        topK: Int,
        wantedIds: Set<Long> = emptySet(),
        expectedCount: Int = 0,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        nextPage: suspend (afterId: Long, limit: Int) -> List<ChunkEmbeddingRow>
    ): VectorScanResult {
        val keep = topK.coerceAtLeast(0)
        // Min-heap on "worst first": lowest similarity, and for equal similarity the highest id.
        val heap = PriorityQueue<ScoredId>(keep.coerceAtLeast(1) + 1) { a, b ->
            val bySim = a.sim.compareTo(b.sim)
            if (bySim != 0) bySim else b.id.compareTo(a.id)
        }
        var sims = FloatArray(maxOf(expectedCount + 16, 64))
        var count = 0
        val wanted = HashMap<Long, Float>()
        var afterId = Long.MIN_VALUE

        while (true) {
            val rows = nextPage(afterId, pageSize)
            if (rows.isEmpty()) break
            for (row in rows) {
                val comparable = modelId == null || ChunkMetadata.model(row.metadata) == modelId
                val raw = if (comparable) {
                    VectorSimilarityUtils.cosineSimilarityWithEmbeddingBlob(query, row.embeddingBlob)
                } else {
                    0f
                }
                val sim = if (raw == 0f) 0f else raw // folds -0.0 into 0.0 so ordering is consistent
                if (count == sims.size) sims = sims.copyOf(sims.size * 2)
                sims[count++] = sim
                if (row.id in wantedIds) wanted[row.id] = sim

                if (keep > 0) {
                    if (heap.size < keep) {
                        heap.add(ScoredId(row.id, sim))
                    } else {
                        val worst = heap.peek()
                        // Rows arrive in ascending id order, so on a tie the earlier id already held wins.
                        if (sim > worst.sim) {
                            heap.poll()
                            heap.add(ScoredId(row.id, sim))
                        }
                    }
                }
            }
            afterId = rows.last().id
            if (rows.size < pageSize) break
        }

        val ordered = heap.toMutableList().apply {
            sortWith { a, b ->
                val bySim = b.sim.compareTo(a.sim)
                if (bySim != 0) bySim else a.id.compareTo(b.id)
            }
        }
        val sorted = sims.copyOf(count).also { it.sort() }
        return VectorScanResult(
            topIds = LongArray(ordered.size) { ordered[it].id },
            topSims = FloatArray(ordered.size) { ordered[it].sim },
            wantedSims = wanted,
            sortedSims = sorted,
            scanned = count
        )
    }

    private class ScoredId(val id: Long, val sim: Float)
}
