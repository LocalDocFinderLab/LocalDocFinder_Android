package com.example

import com.example.data.local.ChunkEmbeddingRow
import com.example.engine.VectorScanner
import com.example.engine.VectorSimilarityUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorScannerTest {

    private fun unit(vararg v: Float): FloatArray = VectorSimilarityUtils.l2Normalize(floatArrayOf(*v))

    // Unquantised blobs keep the ordering assertions exact even for tiny 2-3 dimensional test vectors.
    private fun row(id: Long, vec: FloatArray, model: String = "m1", quantized: Boolean = false) =
        ChunkEmbeddingRow(id, VectorSimilarityUtils.floatArrayToByteArray(vec, useTfLiteQuantization = quantized), "model=$model")

    /** In-memory stand-in for `DocumentChunkDao.getEmbeddingPage`. */
    private fun pager(rows: List<ChunkEmbeddingRow>, calls: IntArray? = null): suspend (Long, Int) -> List<ChunkEmbeddingRow> =
        { after, limit ->
            calls?.let { it[0]++ }
            rows.filter { it.id > after }.sortedBy { it.id }.take(limit)
        }

    @Test
    fun keepsOnlyTheBestMatchesInOrder() = runBlocking {
        val query = unit(1f, 0f, 0f)
        val rows = listOf(
            row(1, unit(0f, 1f, 0f)),   // orthogonal
            row(2, unit(1f, 0.1f, 0f)), // very close
            row(3, unit(1f, 1f, 0f)),   // 45 degrees
            row(4, unit(-1f, 0f, 0f)),  // opposite
            row(5, unit(1f, 0.5f, 0f))  // close
        )
        val result = VectorScanner.scan(query, "m1", topK = 3, pageSize = 2, nextPage = pager(rows))

        assertArrayEquals(longArrayOf(2, 5, 3), result.topIds)
        assertTrue(result.topSims[0] > result.topSims[1] && result.topSims[1] > result.topSims[2])
        assertEquals(5, result.scanned)
        assertEquals(1, result.rankOf(2, result.topSims[0]))
        assertEquals(3, result.rankOf(3, result.topSims[2]))
    }

    @Test
    fun pagesThroughEveryRowExactlyOnce() = runBlocking {
        val query = unit(1f, 0f)
        val rows = (1L..25L).map { row(it, unit(1f, it.toFloat())) }
        val calls = intArrayOf(0)
        val result = VectorScanner.scan(query, "m1", topK = 5, pageSize = 10, nextPage = pager(rows, calls))

        assertEquals(25, result.scanned)
        assertEquals(3, calls[0]) // 10 + 10 + 5; a short last page ends the scan without an extra query
        assertArrayEquals(longArrayOf(1, 2, 3, 4, 5), result.topIds)
    }

    @Test
    fun otherModelsAndMismatchedDimensionsScoreZero() = runBlocking {
        val query = unit(1f, 0f, 0f)
        val rows = listOf(
            row(1, unit(1f, 0f, 0f), model = "other"),
            row(2, unit(1f, 0f)),          // wrong dimension
            row(3, unit(1f, 0.2f, 0f))
        )
        val result = VectorScanner.scan(query, "m1", topK = 3, nextPage = pager(rows))

        assertEquals(3L, result.topIds[0])
        assertEquals(0f, result.topSims[1], 0f)
        assertEquals(0f, result.topSims[2], 0f)
        // Ties keep insertion (id) order.
        assertEquals(1L, result.topIds[1])
        assertEquals(2L, result.topIds[2])
    }

    @Test
    fun nullModelComparesEverything() = runBlocking {
        val query = unit(1f, 0f)
        val rows = listOf(row(1, unit(1f, 0f), model = "anything"))
        val result = VectorScanner.scan(query, null, topK = 1, nextPage = pager(rows))
        assertTrue(result.topSims[0] > 0.99f)
    }

    @Test
    fun wantedIdsGetASimilarityAndRankEvenOutsideTheTopK() = runBlocking {
        val query = unit(1f, 0f)
        val rows = (1L..10L).map { row(it, unit(1f, it.toFloat())) } // 1 is closest ... 10 is furthest
        val result = VectorScanner.scan(query, "m1", topK = 2, wantedIds = setOf(7L, 1L), nextPage = pager(rows))

        assertArrayEquals(longArrayOf(1, 2), result.topIds)
        val sim7 = result.wantedSims.getValue(7L)
        assertTrue(sim7 > 0f && sim7 < result.topSims[1])
        assertEquals(7, result.rankOf(7L, sim7)) // six chunks are more similar
        assertTrue(result.wantedSims.containsKey(1L))
    }

    @Test
    fun topKZeroStillTracksWantedIds() = runBlocking {
        val query = unit(1f, 0f)
        val rows = (1L..4L).map { row(it, unit(1f, it.toFloat())) }
        val result = VectorScanner.scan(query, "m1", topK = 0, wantedIds = setOf(3L), nextPage = pager(rows))

        assertEquals(0, result.topIds.size)
        assertEquals(3, result.rankOf(3L, result.wantedSims.getValue(3L)))
    }

    @Test
    fun emptyCorpusIsFine() = runBlocking {
        val result = VectorScanner.scan(unit(1f, 0f), "m1", topK = 5, nextPage = pager(emptyList()))
        assertEquals(0, result.scanned)
        assertEquals(0, result.topIds.size)
    }

    @Test
    fun quantizedBlobsRankLikeTheFloatVectors() = runBlocking {
        val dim = 64
        val random = java.util.Random(7)
        val vectors = (1L..40L).associateWith { VectorSimilarityUtils.l2Normalize(FloatArray(dim) { random.nextGaussian().toFloat() }) }
        val query = VectorSimilarityUtils.l2Normalize(FloatArray(dim) { random.nextGaussian().toFloat() })
        val rows = vectors.map { (id, v) -> row(id, v, quantized = true) }

        val result = VectorScanner.scan(query, "m1", topK = 5, nextPage = pager(rows))

        val exactBest = vectors.entries
            .sortedByDescending { VectorSimilarityUtils.calculateCosineSimilarity(query, it.value) }
            .take(5).map { it.key }
        // int8 quantisation may swap near-ties but the best matches must still be found.
        assertTrue(result.topIds.toSet().intersect(exactBest.toSet()).size >= 4)
        result.topIds.indices.forEach { i ->
            val exact = VectorSimilarityUtils.calculateCosineSimilarity(query, vectors.getValue(result.topIds[i]))
            assertEquals(exact, result.topSims[i], 0.05f)
        }
    }
}
