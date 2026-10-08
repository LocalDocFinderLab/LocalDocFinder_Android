package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for storing and managing local document paths selected by the user.
 */
@Dao
interface DocumentPathDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPath(path: DocumentPathEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaths(paths: List<DocumentPathEntity>): List<Long>

    @Update
    suspend fun updatePath(path: DocumentPathEntity)

    @Query("SELECT * FROM document_paths ORDER BY addedAt DESC")
    fun getAllPathsFlow(): Flow<List<DocumentPathEntity>>

    @Query("SELECT * FROM document_paths ORDER BY addedAt DESC")
    suspend fun getAllPaths(): List<DocumentPathEntity>

    @Query("SELECT * FROM document_paths WHERE uri = :uri LIMIT 1")
    suspend fun getPathByUri(uri: String): DocumentPathEntity?

    @Query("SELECT * FROM document_paths WHERE status = :status ORDER BY addedAt ASC")
    suspend fun getPathsByStatus(status: String): List<DocumentPathEntity>

    @Query("DELETE FROM document_paths WHERE uri = :uri")
    suspend fun deletePathByUri(uri: String)

    @Query("DELETE FROM document_paths WHERE id = :id")
    suspend fun deletePathById(id: Long)

    @Query("DELETE FROM document_paths")
    suspend fun clearAllPaths()

    @Query("UPDATE document_paths SET status = :status WHERE uri = :uri")
    suspend fun updateStatus(uri: String, status: String)

    @Query("SELECT COUNT(*) FROM document_paths")
    fun getPathCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM document_paths")
    suspend fun getPathCount(): Int
}
