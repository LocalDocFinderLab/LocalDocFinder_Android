package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight projection holding only a chunk's id and its (quantized) embedding BLOB,
 * so semantic re-ranking never has to load full chunk text for every candidate.
 */
data class ChunkEmbeddingRow(
    val id: Long,
    val embeddingBlob: ByteArray
)

@Dao
interface DocumentChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: DocumentChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(fts: DocumentChunkFtsEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFtsList(ftsList: List<DocumentChunkFtsEntity>)

    @Transaction
    suspend fun insertChunksWithFts(chunks: List<DocumentChunkEntity>) {
        val ids = insertChunks(chunks)
        val ftsList = chunks.zip(ids) { chunk, id ->
            DocumentChunkFtsEntity(
                chunkId = id,
                chunkText = chunk.chunkText,
                fileName = chunk.fileName,
                fileUri = chunk.fileUri,
                tags = chunk.tags
            )
        }
        insertFtsList(ftsList)
    }

    // --- Tagging System Queries ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: DocumentTagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<DocumentTagEntity>)

    @Query("DELETE FROM document_tags WHERE fileUri = :fileUri AND tag = :tag")
    suspend fun deleteTag(fileUri: String, tag: String)

    @Query("DELETE FROM document_tags WHERE fileUri = :fileUri")
    suspend fun deleteTagsForFile(fileUri: String)

    @Query("SELECT tag FROM document_tags WHERE fileUri = :fileUri ORDER BY tag ASC")
    suspend fun getTagsForFile(fileUri: String): List<String>

    @Query("SELECT tag FROM document_tags WHERE fileUri = :fileUri ORDER BY tag ASC")
    fun getTagsForFileFlow(fileUri: String): Flow<List<String>>

    @Query("SELECT DISTINCT tag FROM document_tags ORDER BY tag ASC")
    fun getAllDistinctTags(): Flow<List<String>>

    @Query("SELECT DISTINCT tag FROM document_tags ORDER BY tag ASC")
    suspend fun getAllDistinctTagsDirect(): List<String>

    @Query("SELECT DISTINCT fileUri FROM document_tags WHERE tag = :tag")
    suspend fun getFileUrisForTag(tag: String): List<String>

    @Query("UPDATE documents SET tags = :tags WHERE fileUri = :fileUri")
    suspend fun updateChunkTagsForFile(fileUri: String, tags: String)

    @Query("UPDATE documents_fts SET tags = :tags WHERE fileUri = :fileUri")
    suspend fun updateFtsTagsForFile(fileUri: String, tags: String)

    @Transaction
    suspend fun setTagsForFile(fileUri: String, tags: List<String>) {
        deleteTagsForFile(fileUri)
        val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanTags.isNotEmpty()) {
            val tagEntities = cleanTags.map { DocumentTagEntity(fileUri = fileUri, tag = it) }
            insertTags(tagEntities)
        }
        val tagsString = cleanTags.joinToString(", ")
        updateChunkTagsForFile(fileUri, tagsString)
        try {
            updateFtsTagsForFile(fileUri, tagsString)
        } catch (_: Exception) {}
    }

    @Query("SELECT * FROM documents WHERE hash = :hash LIMIT 1")
    suspend fun getChunkByHash(hash: String): DocumentChunkEntity?

    @Query("SELECT * FROM documents WHERE fileUri = :fileUri ORDER BY chunkIndex ASC")
    suspend fun getChunksForFile(fileUri: String): List<DocumentChunkEntity>

    @Query("SELECT * FROM documents")
    suspend fun getAllChunks(): List<DocumentChunkEntity>

    @Query("SELECT id, embeddingBlob FROM documents WHERE id IN (:chunkIds)")
    suspend fun getEmbeddingsForChunkIds(chunkIds: List<Long>): List<ChunkEmbeddingRow>

    @Query("SELECT * FROM documents")
    fun getAllChunksFlow(): Flow<List<DocumentChunkEntity>>

    @Query("SELECT COUNT(*) FROM documents")
    fun getTotalChunksCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun getTotalChunksCountDirect(): Int

    @Query("SELECT COUNT(DISTINCT fileUri) FROM documents")
    fun getTotalFilesCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT fileUri) FROM documents")
    suspend fun getTotalFilesCountDirect(): Int

    @Query("SELECT DISTINCT fileUri FROM documents")
    fun getIndexedFiles(): Flow<List<String>>

    @Query("SELECT DISTINCT fileUri FROM documents")
    suspend fun getIndexedFilesDirect(): List<String>

    @Query("""
        SELECT d.* FROM documents d
        JOIN documents_fts ON d.id = documents_fts.chunkId
        WHERE documents_fts MATCH :ftsQuery
        LIMIT :limit
    """)
    suspend fun searchFts(ftsQuery: String, limit: Int = 100): List<DocumentChunkEntity>

    @Query("""
        SELECT chunkId FROM documents_fts 
        WHERE documents_fts MATCH :ftsQuery 
        LIMIT :limit
    """)
    suspend fun searchFtsChunkIds(ftsQuery: String, limit: Int = 100): List<Long>

    @Query("""
        SELECT * FROM documents 
        WHERE chunkText LIKE '%' || :query || '%' 
           OR fileName LIKE '%' || :query || '%'
        LIMIT :limit
    """)
    suspend fun searchFallbackLike(query: String, limit: Int = 100): List<DocumentChunkEntity>

    @Query("DELETE FROM documents WHERE fileUri = :fileUri")
    suspend fun deleteChunksByFile(fileUri: String)

    @Query("""
        DELETE FROM documents_fts 
        WHERE chunkId IN (SELECT id FROM documents WHERE fileUri = :fileUri)
    """)
    suspend fun deleteFtsByFile(fileUri: String)

    @Transaction
    suspend fun deleteFileRecord(fileUri: String) {
        deleteFtsByFile(fileUri)
        deleteChunksByFile(fileUri)
        deleteTagsForFile(fileUri)
    }

    @Query("DELETE FROM documents WHERE fileUri IN (:fileUris)")
    suspend fun deleteChunksByFiles(fileUris: List<String>)

    @Query("""
        DELETE FROM documents_fts 
        WHERE chunkId IN (SELECT id FROM documents WHERE fileUri IN (:fileUris))
    """)
    suspend fun deleteFtsByFiles(fileUris: List<String>)

    @Query("DELETE FROM document_tags WHERE fileUri IN (:fileUris)")
    suspend fun deleteTagsForFiles(fileUris: List<String>)

    @Transaction
    suspend fun deleteFileRecords(fileUris: List<String>) {
        if (fileUris.isEmpty()) return
        deleteFtsByFiles(fileUris)
        deleteChunksByFiles(fileUris)
        deleteTagsForFiles(fileUris)
    }

    @Query("DELETE FROM documents WHERE id IN (:chunkIds)")
    suspend fun deleteChunksByIds(chunkIds: List<Long>)

    @Query("DELETE FROM documents_fts WHERE chunkId IN (:chunkIds)")
    suspend fun deleteFtsByIds(chunkIds: List<Long>)

    @Transaction
    suspend fun deleteChunkRecords(chunkIds: List<Long>) {
        if (chunkIds.isEmpty()) return
        deleteFtsByIds(chunkIds)
        deleteChunksByIds(chunkIds)
    }

    @Query("DELETE FROM documents")
    suspend fun clearDocuments()

    @Query("DELETE FROM documents_fts")
    suspend fun clearFts()

    @Query("DELETE FROM document_tags")
    suspend fun clearTags()

    @Transaction
    suspend fun clearAll() {
        clearFts()
        clearDocuments()
        clearTags()
    }
}
