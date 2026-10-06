package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.engine.SearchResult
import com.example.engine.TensorFlowLiteQuantizer
import com.example.engine.VectorSimilarityUtils
import com.example.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SemanticFtsRankingTest {

    private fun unitVector(dim: Int, angle: Double): FloatArray {
        // Vector in the plane spanned by the first two axes, rotated by [angle] from axis 0.
        return FloatArray(dim).also {
            it[0] = cos(angle).toFloat()
            it[1] = sin(angle).toFloat()
        }
    }

    private fun ftsResult(id: Long, bm25: Float) = SearchResult(
        chunkId = id,
        fileUri = "file:///docs/doc$id.txt",
        fileName = "doc$id.txt",
        chunkIndex = 0,
        chunkText = "text $id",
        snippet = "text $id",
        highlightedTerms = emptyList(),
        cosineSimilarity = 0f,
        vectorRank = null,
        ftsRank = id.toInt(),
        rrfScore = 0f,
        bm25Score = bm25,
        latencyMs = 1L
    )

    @Test
    fun quantizedCosineMatchesDequantizedFloatCosine() {
        val dim = 64
        val doc = FloatArray(dim) { i -> sin(i * 0.37f) + 0.2f }
        val query = FloatArray(dim) { i -> cos(i * 0.21f) - 0.1f }
        val blob = TensorFlowLiteQuantizer.compressToQuantizedBlob(doc)

        val viaKernel = TensorFlowLiteQuantizer.cosineSimilarityWithQuantizedBlob(query, blob)
        val viaDequantized = VectorSimilarityUtils.calculateCosineSimilarity(
            query,
            TensorFlowLiteQuantizer.decompressFromQuantizedBlob(blob)
        )
        val viaFloat = VectorSimilarityUtils.calculateCosineSimilarity(query, doc)

        assertTrue("kernel=$viaKernel dequantized=$viaDequantized", abs(viaKernel - viaDequantized) < 1e-4f)
        assertTrue("kernel=$viaKernel float=$viaFloat", abs(viaKernel - viaFloat) < 0.02f)
        assertEquals(dim, TensorFlowLiteQuantizer.blobDimension(blob))
    }

    @Test
    fun cosineKernelHandlesLegacyAndMismatchedBlobs() {
        val query = unitVector(8, 0.0)
        val legacyBlob = VectorSimilarityUtils.floatArrayToByteArrayUnquantized(unitVector(8, 0.0))
        assertEquals(1f, VectorSimilarityUtils.cosineSimilarityWithEmbeddingBlob(query, legacyBlob), 1e-5f)
        assertEquals(8, TensorFlowLiteQuantizer.blobDimension(legacyBlob))

        val otherDimBlob = TensorFlowLiteQuantizer.compressToQuantizedBlob(unitVector(16, 0.0))
        assertEquals(0f, VectorSimilarityUtils.cosineSimilarityWithEmbeddingBlob(query, otherDimBlob), 0f)
        assertEquals(0f, VectorSimilarityUtils.cosineSimilarityWithEmbeddingBlob(query, ByteArray(0)), 0f)
    }

    @Test
    fun ftsResultsAreRankedBySemanticRelevanceNotKeywordScore() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        val dim = 32
        val query = unitVector(dim, 0.0)

        // Chunk 1 has the best keyword score but is semantically far away; chunk 3 is nearly identical to the query.
        val blobs = mapOf(
            1L to TensorFlowLiteQuantizer.compressToQuantizedBlob(unitVector(dim, 1.3)),
            2L to TensorFlowLiteQuantizer.compressToQuantizedBlob(unitVector(dim, 0.6)),
            3L to TensorFlowLiteQuantizer.compressToQuantizedBlob(unitVector(dim, 0.05))
        )
        val fts = listOf(ftsResult(1, 0.95f), ftsResult(2, 0.60f), ftsResult(3, 0.10f))

        val ranked = vm.rankBySemanticRelevance(fts, query, blobs)

        assertEquals(listOf(3L, 2L, 1L), ranked.map { it.chunkId })
        assertTrue(ranked[0].cosineSimilarity > ranked[1].cosineSimilarity)
        assertTrue(ranked[1].cosineSimilarity > ranked[2].cosineSimilarity)
        assertEquals(ranked[0].cosineSimilarity, ranked[0].combinedScore, 1e-6f)
    }

    @Test
    fun chunksWithoutEmbeddingKeepBm25ScoreAndSortLast() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        val query = unitVector(16, 0.0)
        val blobs = mapOf(2L to TensorFlowLiteQuantizer.compressToQuantizedBlob(unitVector(16, 0.2)))

        val ranked = vm.rankBySemanticRelevance(listOf(ftsResult(1, 0.05f), ftsResult(2, 0.4f)), query, blobs)

        assertEquals(2L, ranked[0].chunkId)
        assertEquals(0f, ranked[1].cosineSimilarity, 0f)
        assertEquals(0.05f, ranked[1].combinedScore, 1e-6f)
    }
}
