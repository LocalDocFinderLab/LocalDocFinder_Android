package com.example

import com.example.engine.TensorFlowLiteQuantizer
import com.example.engine.VectorSimilarityUtils
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
}
