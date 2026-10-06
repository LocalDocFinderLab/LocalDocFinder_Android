package com.example.engine.embedding

import kotlin.math.sqrt

/** How a transformer's per-token hidden states are reduced to one sentence vector. */
enum class Pooling {
    /** First ([CLS]) token's hidden state — what BGE models are trained with. */
    CLS,

    /** Attention-mask-weighted mean of all token states — what MiniLM / E5 / GTE are trained with. */
    MEAN
}

/** Pure-Kotlin pooling + normalisation so it can be unit-tested on the JVM. */
object EmbeddingPooling {

    /**
     * @param hidden row-major token states, `hidden[t * dimension + d]`, at least `tokens * dimension` long
     * @param attentionMask 1 for real tokens, 0 for padding; `attentionMask.size` is the token count
     * @return the L2-normalised sentence vector of length [dimension]
     */
    fun pool(hidden: FloatArray, attentionMask: IntArray, dimension: Int, pooling: Pooling): FloatArray {
        val tokens = attentionMask.size
        require(dimension > 0) { "dimension must be positive" }
        require(hidden.size >= tokens * dimension) {
            "hidden state has ${hidden.size} values, need ${tokens * dimension} ($tokens tokens x $dimension)"
        }
        val out = FloatArray(dimension)
        when (pooling) {
            Pooling.CLS -> System.arraycopy(hidden, 0, out, 0, dimension)
            Pooling.MEAN -> {
                var count = 0
                for (t in 0 until tokens) {
                    if (attentionMask[t] == 0) continue
                    count++
                    val base = t * dimension
                    for (d in 0 until dimension) out[d] += hidden[base + d]
                }
                if (count > 0) for (d in 0 until dimension) out[d] /= count
            }
        }
        return l2Normalize(out)
    }

    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v.toDouble() * v
        val norm = sqrt(sum).toFloat()
        if (norm < 1e-12f || norm.isNaN()) return vector
        val out = FloatArray(vector.size)
        for (i in vector.indices) out[i] = vector[i] / norm
        return out
    }
}
