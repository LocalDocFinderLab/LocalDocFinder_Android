package com.example.data.local

import androidx.room.TypeConverter
import com.example.engine.VectorSimilarityUtils

/**
 * Room TypeConverters for document chunk entities and vector search.
 * Provides bidirectional conversion between FloatArray vector embeddings and ByteArray (SQLite BLOB).
 */
class Converters {

    @TypeConverter
    fun fromFloatArray(vector: FloatArray?): ByteArray? {
        if (vector == null) return null
        return VectorSimilarityUtils.floatArrayToByteArray(vector)
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null) return null
        return VectorSimilarityUtils.byteArrayToFloatArray(bytes)
    }
}
