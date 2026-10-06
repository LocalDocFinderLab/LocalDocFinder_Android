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
    val embeddingBlob: ByteArray,
    val metadata: String
)

/** When a file was last indexed (its stored source timestamp) and how many chunks it currently has. */
data class FileIndexStamp(
    val lastIndexed: Long?,
    val chunkCount: Int
)

/** Per-file last-indexed timestamp, used by background scans to find new or modified files cheaply. */
data class IndexedFileStamp(
    val fileUri: String,
    val lastIndexed: Long
)

@Dao
interface DocumentChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: DocumentChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>): List<Long>

    /**
     * Inserts chunks. The `documents_fts` index is an external-content FTS4 table over `documents`,
     * so Room's generated triggers keep it in sync automatically inside the same transaction.
     */
    @Transaction
    suspend fun insertChunksWithFts(chunks: List<DocumentChunkEntity>) {
        insertChunks(chunks)
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

    @Query("SELECT * FROM documents WHERE hash = :hash LIMIT 1")
    suspend fun getChunkByHash(hash: String): DocumentChunkEntity?

    @Query("SELECT * FROM documents WHERE fileUri = :fileUri ORDER BY chunkIndex ASC")
    suspend fun getChunksForFile(fileUri: String): List<DocumentChunkEntity>

    @Query("SELECT * FROM documents")
    suspend fun getAllChunks(): List<DocumentChunkEntity>

    @Query("SELECT id, embeddingBlob, metadata FROM documents WHERE id IN (:chunkIds)")
    suspend fun getEmbeddingsForChunkIds(chunkIds: List<Long>): List<ChunkEmbeddingRow>

    /**
     * Number of chunks whose vector was NOT produced by [modelId] (including chunks indexed before models
     * were tagged). Those chunks don't take part in semantic scoring until they are re-indexed.
     * `metadata` starts with `model=<id>` followed by end-of-string or `;` (see ChunkMetadata).
     */
    @Query(
        "SELECT COUNT(*) FROM documents WHERE NOT (metadata = 'model=' || :modelId " +
            "OR substr(metadata, 1, length(:modelId) + 7) = 'model=' || :modelId || ';')"
    )
    fun countChunksNotIndexedWith(modelId: String): Flow<Int>

    @Query("SELECT MAX(timestamp) AS lastIndexed, COUNT(*) AS chunkCount FROM documents WHERE fileUri = :fileUri")
    suspend fun getFileIndexStamp(fileUri: String): FileIndexStamp

    @Query("SELECT fileUri, MAX(timestamp) AS lastIndexed FROM documents GROUP BY fileUri")
    suspend fun getIndexedFileStamps(): List<IndexedFileStamp>

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
        JOIN documents_fts ON d.id = documents_fts.rowid
        WHERE documents_fts MATCH :ftsQuery
        LIMIT :limit
    """)
    suspend fun searchFts(ftsQuery: String, limit: Int = 100): List<DocumentChunkEntity>

    @Query("""
        SELECT rowid FROM documents_fts 
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

    @Transaction
    suspend fun deleteFileRecord(fileUri: String) {
        deleteChunksByFile(fileUri)
        deleteTagsForFile(fileUri)
    }

    @Query("DELETE FROM documents WHERE fileUri IN (:fileUris)")
    suspend fun deleteChunksByFiles(fileUris: List<String>)

    @Query("DELETE FROM document_tags WHERE fileUri IN (:fileUris)")
    suspend fun deleteTagsForFiles(fileUris: List<String>)

    @Transaction
    suspend fun deleteFileRecords(fileUris: List<String>) {
        if (fileUris.isEmpty()) return
        deleteChunksByFiles(fileUris)
        deleteTagsForFiles(fileUris)
    }

    @Query("DELETE FROM documents WHERE id IN (:chunkIds)")
    suspend fun deleteChunksByIds(chunkIds: List<Long>)

    @Transaction
    suspend fun deleteChunkRecords(chunkIds: List<Long>) {
        if (chunkIds.isEmpty()) return
        deleteChunksByIds(chunkIds)
    }

    @Query("DELETE FROM documents")
    suspend fun clearDocuments()

    @Query("DELETE FROM document_tags")
    suspend fun clearTags()

    @Transaction
    suspend fun clearAll() {
        clearDocuments()
        clearTags()
    }
}
