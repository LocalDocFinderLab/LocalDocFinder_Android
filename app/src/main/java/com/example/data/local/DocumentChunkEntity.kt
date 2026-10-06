package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.engine.VectorSimilarityUtils

/**
 * Room Entity for storing document chunks, including columns for:
 * - Raw text content ([chunkText])
 * - Associated metadata ([fileUri], [fileName], [chunkIndex], [hash], [timestamp], [tags], [metadata])
 * - Vector embeddings stored as BLOB / FloatArray ([embeddingBlob], accessible via [embedding])
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["fileUri"]),
        Index(value = ["hash"])
    ]
)
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "fileUri")
    val fileUri: String,

    @ColumnInfo(name = "fileName")
    val fileName: String,

    @ColumnInfo(name = "chunkIndex")
    val chunkIndex: Int,

    @ColumnInfo(name = "chunkText")
    val chunkText: String,

    @ColumnInfo(name = "hash")
    val hash: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "embeddingBlob", typeAffinity = ColumnInfo.BLOB)
    val embeddingBlob: ByteArray,

    @ColumnInfo(name = "tags")
    val tags: String = "",

    @ColumnInfo(name = "metadata")
    val metadata: String = ""
) {
    /**
     * Exposes the raw text content of this chunk.
     */
    val content: String
        get() = chunkText

    /**
     * Exposes the vector embedding as a [FloatArray] for in-memory vector similarity calculations.
     */
    val embedding: FloatArray
        get() = VectorSimilarityUtils.byteArrayToFloatArray(embeddingBlob)

    /**
     * Secondary constructor allowing direct initialization with a [FloatArray] vector embedding.
     */
    constructor(
        id: Long = 0,
        fileUri: String,
        fileName: String,
        chunkIndex: Int,
        chunkText: String,
        hash: String,
        timestamp: Long,
        embedding: FloatArray,
        tags: String = "",
        metadata: String = ""
    ) : this(
        id = id,
        fileUri = fileUri,
        fileName = fileName,
        chunkIndex = chunkIndex,
        chunkText = chunkText,
        hash = hash,
        timestamp = timestamp,
        embeddingBlob = VectorSimilarityUtils.floatArrayToByteArray(embedding),
        tags = tags,
        metadata = metadata
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DocumentChunkEntity
        return id == other.id && fileUri == other.fileUri && hash == other.hash
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fileUri.hashCode()
        result = 31 * result + hash.hashCode()
        return result
    }
}
