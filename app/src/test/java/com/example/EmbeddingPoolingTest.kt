package com.example

import com.example.engine.embedding.EmbeddingPooling
import com.example.engine.embedding.Pooling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class EmbeddingPoolingTest {

    // 3 tokens x 2 dims; the last token is padding and must be ignored by MEAN pooling.
    private val hidden = floatArrayOf(
        3f, 0f,   // [CLS]
        1f, 4f,   // real token
        99f, 99f  // padding
    )
    private val mask = intArrayOf(1, 1, 0)

    private fun norm(v: FloatArray) = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()

    @Test
    fun `CLS pooling takes the first token and normalises`() {
        val v = EmbeddingPooling.pool(hidden, mask, 2, Pooling.CLS)
        assertArrayEquals(floatArrayOf(1f, 0f), v, 1e-6f)
    }

    @Test
    fun `MEAN pooling averages only unmasked tokens`() {
        val v = EmbeddingPooling.pool(hidden, mask, 2, Pooling.MEAN)
        // mean of (3,0) and (1,4) = (2,2) -> normalised
        val expected = 1f / sqrt(2f)
        assertArrayEquals(floatArrayOf(expected, expected), v, 1e-6f)
        assertEquals(1f, norm(v), 1e-6f)
    }

    @Test
    fun `zero vector stays zero instead of becoming NaN`() {
        val v = EmbeddingPooling.l2Normalize(floatArrayOf(0f, 0f, 0f))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), v, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short hidden buffer is rejected`() {
        EmbeddingPooling.pool(floatArrayOf(1f, 2f), intArrayOf(1, 1), 2, Pooling.MEAN)
    }
}
