package com.example.engine

import com.example.data.local.DocumentChunkEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Utility class for vector operations, similarity calculations, and Room database vector search.
 * Designed for BERT embeddings (e.g. 384-dimensional, 512-dimensional, or 768-dimensional float vectors).
 */
object VectorSimilarityUtils {

    /**
     * Represents a vector search match result from Room database queries.
     */
    data class VectorMatch(
        val chunk: DocumentChunkEntity,
        val similarity: Float,
        val rank: Int
    )

    /**
     * Calculates the cosine similarity between two float arrays (BERT embeddings).
     *
     * Cosine similarity formula:
     *   sim(A, B) = (A · B) / (||A|| * ||B||)
     *
     * If both vectors are already L2-normalized (||A|| = 1, ||B|| = 1), this reduces to the dot product.
     * Handles mismatched dimensions, zero magnitude vectors, and NaN/Infinity values safely.
     *
     * @param vecA First embedding vector
     * @param vecB Second embedding vector
     * @return Cosine similarity score in range [-1.0, 1.0]. Returns 0.0 if vectors are invalid or orthogonal.
     */
    @JvmStatic
    fun calculateCosineSimilarity(vecA: FloatArray?, vecB: FloatArray?): Float {
        if (vecA == null || vecB == null) return 0f
        val dimension = vecA.size
        if (dimension == 0 || dimension != vecB.size) return 0f

        var dotProduct = 0.0
        var normASquared = 0.0
        var normBSquared = 0.0

        for (i in 0 until dimension) {
            val a = vecA[i].toDouble()
            val b = vecB[i].toDouble()
            dotProduct += a * b
            normASquared += a * a
            normBSquared += b * b
        }

        if (normASquared <= 1e-12 || normBSquared <= 1e-12) {
            return 0f
        }

        val similarity = dotProduct / (sqrt(normASquared) * sqrt(normBSquared))
        if (similarity.isNaN() || similarity.isInfinite()) {
            return 0f
        }

        return similarity.toFloat().coerceIn(-1.0f, 1.0f)
    }

    /**
     * Optimized cosine similarity calculation when both vectors are guaranteed to be L2-normalized.
     * Directly computes the inner dot product.
     *
     * @param normVecA L2-normalized embedding vector A
     * @param normVecB L2-normalized embedding vector B
     * @return Cosine similarity score in range [-1.0, 1.0]
     */
    @JvmStatic
    fun calculateCosineSimilarityNormalized(normVecA: FloatArray, normVecB: FloatArray): Float {
        if (normVecA.size != normVecB.size || normVecA.isEmpty()) return 0f
        var dot = 0f
        for (i in normVecA.indices) {
            dot += normVecA[i] * normVecB[i]
        }
        return dot.coerceIn(-1.0f, 1.0f)
    }

    /**
     * Calculates the L2 (Euclidean) norm (magnitude) of a vector.
     */
    @JvmStatic
    fun l2Norm(vector: FloatArray): Float {
        var sumSq = 0.0
        for (v in vector) {
            sumSq += (v * v).toDouble()
        }
        return sqrt(sumSq).toFloat()
    }

    /**
     * Normalizes a float vector in-place or returns a new L2-normalized float array.
     * After normalization: ||V||_2 = 1.0.
     *
     * @param vector The input float vector
     * @return A new L2-normalized FloatArray
     */
    @JvmStatic
    fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = l2Norm(vector)
        if (norm < 1e-12f) return vector.copyOf()

        val normalized = FloatArray(vector.size)
        for (i in vector.indices) {
            normalized[i] = vector[i] / norm
        }
        return normalized
    }

    /**
     * Computes the dot product between two float vectors.
     */
    @JvmStatic
    fun dotProduct(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size) return 0f
        var sum = 0f
        for (i in vecA.indices) {
            sum += vecA[i] * vecB[i]
        }
        return sum
    }

    /**
     * Computes Euclidean distance between two vectors: sqrt(sum((a_i - b_i)^2)).
     */
    @JvmStatic
    fun euclideanDistance(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size || vecA.isEmpty()) return Float.MAX_VALUE
        var sumSq = 0.0
        for (i in vecA.indices) {
            val diff = (vecA[i] - vecB[i]).toDouble()
            sumSq += diff * diff
        }
        return sqrt(sumSq).toFloat()
    }

    /**
     * Computes Manhattan (L1) distance between two vectors: sum(|a_i - b_i|).
     */
    @JvmStatic
    fun manhattanDistance(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size || vecA.isEmpty()) return Float.MAX_VALUE
        var sum = 0f
        for (i in vecA.indices) {
            sum += Math.abs(vecA[i] - vecB[i])
        }
        return sum
    }

    /**
     * Serializes a FloatArray into a TFLite INT8 quantized ByteArray for storage in a Room database BLOB column,
     * reducing local database storage footprint by ~74%.
     *
     * @param vector Float array to serialize
     * @param useTfLiteQuantization Whether to apply TensorFlow Lite INT8 quantization (default true)
     * @return Byte array representation
     */
    @JvmStatic
    fun floatArrayToByteArray(vector: FloatArray, useTfLiteQuantization: Boolean = true): ByteArray {
        if (vector.isEmpty()) return ByteArray(0)
        return if (useTfLiteQuantization) {
            TensorFlowLiteQuantizer.compressToQuantizedBlob(vector)
        } else {
            floatArrayToByteArrayUnquantized(vector)
        }
    }

    /**
     * Serializes a FloatArray without quantization (4 bytes per float).
     */
    @JvmStatic
    fun floatArrayToByteArrayUnquantized(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) {
            buffer.putFloat(v)
        }
        return buffer.array()
    }

    /**
     * Deserializes a ByteArray from a Room database BLOB column back into a FloatArray.
     * Automatically handles both TFLite INT8 quantized BLOBs and legacy unquantized float BLOBs.
     *
     * @param bytes Byte array from Room BLOB
     * @return Deserialized FloatArray
     */
    @JvmStatic
    fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty()) return FloatArray(0)
        return if (TensorFlowLiteQuantizer.isQuantizedBlob(bytes)) {
            TensorFlowLiteQuantizer.decompressFromQuantizedBlob(bytes)
        } else {
            byteArrayToFloatArrayLegacy(bytes)
        }
    }

    /**
     * Legacy deserializer for unquantized 32-bit float BLOBs.
     */
    @JvmStatic
    fun byteArrayToFloatArrayLegacy(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.float
        }
        return floats
    }

    /**
     * Performs vector search (K-Nearest Neighbors using Cosine Similarity) over a list of
     * Room [DocumentChunkEntity] records retrieved from the SQLite database.
     *
     * Deserializes each chunk's embedding BLOB, computes cosine similarity against the query embedding,
     * filters by minimum score threshold, and ranks the results.
     *
     * @param queryEmbedding The BERT query embedding vector (384-d, 512-d, 768-d, etc.)
     * @param chunks List of document chunks loaded from Room database
     * @param topK Maximum number of nearest neighbors to return
     * @param minSimilarity Minimum cosine similarity score threshold (default 0.0)
     * @return Ranked list of [VectorMatch] sorted descending by similarity score
     */
    @JvmStatic
    fun performVectorSearch(
        queryEmbedding: FloatArray,
        chunks: List<DocumentChunkEntity>,
        topK: Int = 30,
        minSimilarity: Float = 0.0f
    ): List<VectorMatch> {
        if (chunks.isEmpty() || queryEmbedding.isEmpty()) return emptyList()

        val normalizedQuery = l2Normalize(queryEmbedding)
        val scoredList = ArrayList<Pair<DocumentChunkEntity, Float>>(chunks.size)

        for (chunk in chunks) {
            val chunkVec = byteArrayToFloatArray(chunk.embeddingBlob)
            if (chunkVec.isEmpty()) continue

            val similarity = calculateCosineSimilarity(normalizedQuery, chunkVec)
            if (similarity >= minSimilarity) {
                scoredList.add(Pair(chunk, similarity))
            }
        }

        scoredList.sortByDescending { it.second }

        val resultSize = min(topK, scoredList.size)
        val results = ArrayList<VectorMatch>(resultSize)
        for (i in 0 until resultSize) {
            val (chunk, sim) = scoredList[i]
            results.add(VectorMatch(chunk = chunk, similarity = sim, rank = i + 1))
        }

        return results
    }

    /**
     * Computes batch cosine similarity scores between a query vector and a list of candidate vectors.
     *
     * @param queryEmbedding Query vector
     * @param candidateEmbeddings List of candidate vectors
     * @return FloatArray of similarity scores corresponding to each candidate
     */
    @JvmStatic
    fun batchCosineSimilarity(
        queryEmbedding: FloatArray,
        candidateEmbeddings: List<FloatArray>
    ): FloatArray {
        val scores = FloatArray(candidateEmbeddings.size)
        val normQuery = l2Normalize(queryEmbedding)
        for (i in candidateEmbeddings.indices) {
            scores[i] = calculateCosineSimilarity(normQuery, candidateEmbeddings[i])
        }
        return scores
    }

    /**
     * Computes the centroid (mean vector) of a set of embedding vectors and L2-normalizes it.
     */
    @JvmStatic
    fun computeCentroid(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(0)
        val dim = embeddings.first().size
        val centroid = FloatArray(dim)

        var validCount = 0
        for (vec in embeddings) {
            if (vec.size == dim) {
                for (i in 0 until dim) {
                    centroid[i] += vec[i]
                }
                validCount++
            }
        }

        if (validCount > 0) {
            for (i in 0 until dim) {
                centroid[i] /= validCount.toFloat()
            }
        }

        return l2Normalize(centroid)
    }
}

/**
 * Convenient alias for backwards compatibility and Java interop.
 */
typealias CosineSimilarityUtils = VectorSimilarityUtils
