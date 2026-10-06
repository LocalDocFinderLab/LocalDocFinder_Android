package com.example

import com.example.data.local.DocumentChunkEntity
import com.example.engine.VectorSimilarityUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorSimilarityUtilsTest {

    @Test
    fun testCosineSimilarityIdenticalAndOppositeVectors() {
        val vecA = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val vecB = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f)
        val vecOpposite = floatArrayOf(-1.0f, -2.0f, -3.0f, -4.0f)
        val vecOrthogonal = floatArrayOf(2.0f, -1.0f, 0.0f, 0.0f)

        val simIdentical = VectorSimilarityUtils.calculateCosineSimilarity(vecA, vecB)
        assertEquals(1.0f, simIdentical, 1e-5f)

        val simOpposite = VectorSimilarityUtils.calculateCosineSimilarity(vecA, vecOpposite)
        assertEquals(-1.0f, simOpposite, 1e-5f)

        val simOrthogonal = VectorSimilarityUtils.calculateCosineSimilarity(vecA, vecOrthogonal)
        assertEquals(0.0f, simOrthogonal, 1e-5f)
    }

    @Test
    fun testCosineSimilarityBertEmbeddingDimensions() {
        // Test with 384-dimensional BERT embedding vectors
        val dim = 384
        val vec1 = FloatArray(dim) { (it % 10).toFloat() }
        val vec2 = FloatArray(dim) { (it % 10).toFloat() + 0.1f }
        val vecRandom = FloatArray(dim) { ((it * 17) % 13 - 6).toFloat() }

        val simClose = VectorSimilarityUtils.calculateCosineSimilarity(vec1, vec2)
        val simFar = VectorSimilarityUtils.calculateCosineSimilarity(vec1, vecRandom)

        assertTrue("Close vectors should have high similarity (>0.95)", simClose > 0.95f)
        assertTrue("Close vectors similarity should be greater than distant vectors", simClose > simFar)
    }

    @Test
    fun testEdgeCasesZeroAndNullVectors() {
        val valid = floatArrayOf(1.0f, 2.0f, 3.0f)
        val zero = floatArrayOf(0.0f, 0.0f, 0.0f)
        val mismatched = floatArrayOf(1.0f, 2.0f)

        assertEquals(0.0f, VectorSimilarityUtils.calculateCosineSimilarity(valid, zero), 1e-5f)
        assertEquals(0.0f, VectorSimilarityUtils.calculateCosineSimilarity(zero, zero), 1e-5f)
        assertEquals(0.0f, VectorSimilarityUtils.calculateCosineSimilarity(valid, mismatched), 1e-5f)
        assertEquals(0.0f, VectorSimilarityUtils.calculateCosineSimilarity(null, valid), 1e-5f)
        assertEquals(0.0f, VectorSimilarityUtils.calculateCosineSimilarity(valid, null), 1e-5f)
    }

    @Test
    fun testL2NormalizationAndL2Norm() {
        val vec = floatArrayOf(3.0f, 4.0f)
        val norm = VectorSimilarityUtils.l2Norm(vec)
        assertEquals(5.0f, norm, 1e-5f)

        val normalized = VectorSimilarityUtils.l2Normalize(vec)
        assertEquals(0.6f, normalized[0], 1e-5f)
        assertEquals(0.8f, normalized[1], 1e-5f)
        assertEquals(1.0f, VectorSimilarityUtils.l2Norm(normalized), 1e-5f)
    }

    @Test
    fun testSerializationDeserializationRoundTrip() {
        // Unquantized 384-d vector serialization to Room BLOB (1536 bytes)
        val original = FloatArray(384) { (it * 0.123f) - 20f }
        val unquantizedBlob = VectorSimilarityUtils.floatArrayToByteArrayUnquantized(original)
        assertEquals(384 * 4, unquantizedBlob.size)

        val deserializedUnquantized = VectorSimilarityUtils.byteArrayToFloatArray(unquantizedBlob)
        assertEquals(384, deserializedUnquantized.size)
        for (i in 0 until 384) {
            assertEquals(original[i], deserializedUnquantized[i], 1e-5f)
        }

        // TFLite INT8 Quantized BLOB serialization (401 bytes)
        val quantizedBlob = VectorSimilarityUtils.floatArrayToByteArray(original)
        assertEquals(401, quantizedBlob.size)

        val deserializedQuantized = VectorSimilarityUtils.byteArrayToFloatArray(quantizedBlob)
        assertEquals(384, deserializedQuantized.size)
        for (i in 0 until 384) {
            assertEquals(original[i], deserializedQuantized[i], 0.25f)
        }
    }

    @Test
    fun testPerformVectorSearchOverRoomEntities() {
        val query = VectorSimilarityUtils.l2Normalize(floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f))

        // Create 3 chunks with different embeddings
        val c1Vec = VectorSimilarityUtils.l2Normalize(floatArrayOf(0.9f, 0.1f, 0.0f, 0.0f))
        val c2Vec = VectorSimilarityUtils.l2Normalize(floatArrayOf(0.5f, 0.5f, 0.0f, 0.0f))
        val c3Vec = VectorSimilarityUtils.l2Normalize(floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f))

        val chunk1 = DocumentChunkEntity(
            id = 1L,
            fileUri = "file:///doc1.pdf",
            fileName = "doc1.pdf",
            chunkIndex = 0,
            chunkText = "Text about AI",
            hash = "hash1",
            timestamp = 1000L,
            embeddingBlob = VectorSimilarityUtils.floatArrayToByteArray(c1Vec)
        )
        val chunk2 = DocumentChunkEntity(
            id = 2L,
            fileUri = "file:///doc2.pdf",
            fileName = "doc2.pdf",
            chunkIndex = 0,
            chunkText = "Text about Search",
            hash = "hash2",
            timestamp = 2000L,
            embeddingBlob = VectorSimilarityUtils.floatArrayToByteArray(c2Vec)
        )
        val chunk3 = DocumentChunkEntity(
            id = 3L,
            fileUri = "file:///doc3.pdf",
            fileName = "doc3.pdf",
            chunkIndex = 0,
            chunkText = "Text about Cooking",
            hash = "hash3",
            timestamp = 3000L,
            embeddingBlob = VectorSimilarityUtils.floatArrayToByteArray(c3Vec)
        )

        val chunks = listOf(chunk3, chunk1, chunk2)
        val matches = VectorSimilarityUtils.performVectorSearch(
            queryEmbedding = query,
            chunks = chunks,
            topK = 2,
            minSimilarity = 0.1f
        )

        assertEquals(2, matches.size)
        // chunk1 should be rank 1 (highest similarity ~0.99)
        assertEquals(1L, matches[0].chunk.id)
        assertEquals(1, matches[0].rank)
        assertTrue(matches[0].similarity > 0.9f)

        // chunk2 should be rank 2 (similarity ~0.707)
        assertEquals(2L, matches[1].chunk.id)
        assertEquals(2, matches[1].rank)
        assertTrue(matches[1].similarity > 0.5f)
    }

    @Test
    fun testDistancesAndCentroid() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(4.0f, 6.0f, 3.0f)

        val euclid = VectorSimilarityUtils.euclideanDistance(a, b)
        assertEquals(5.0f, euclid, 1e-5f) // sqrt((4-1)^2 + (6-2)^2 + (3-3)^2) = sqrt(9+16)=5

        val manhattan = VectorSimilarityUtils.manhattanDistance(a, b)
        assertEquals(7.0f, manhattan, 1e-5f) // |4-1| + |6-2| + |3-3| = 3 + 4 + 0 = 7

        val centroid = VectorSimilarityUtils.computeCentroid(listOf(a, b))
        val expectedUnnorm = floatArrayOf(2.5f, 4.0f, 3.0f)
        val expectedNorm = VectorSimilarityUtils.l2Normalize(expectedUnnorm)

        for (i in centroid.indices) {
            assertEquals(expectedNorm[i], centroid[i], 1e-5f)
        }
    }
}
