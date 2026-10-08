package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a local document path chosen by the user via DocumentFile picker.
 * Stores URI, system path, file display name, file size, last modified timestamp, and indexing status.
 */
@Entity(
    tableName = "document_paths",
    indices = [
        Index(value = ["uri"], unique = true)
    ]
)
data class DocumentPathEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "path")
    val path: String = "",

    @ColumnInfo(name = "displayName")
    val displayName: String = "",

    @ColumnInfo(name = "mimeType")
    val mimeType: String? = null,

    @ColumnInfo(name = "sizeBytes")
    val sizeBytes: Long = 0L,

    @ColumnInfo(name = "lastModified")
    val lastModified: Long = 0L,

    @ColumnInfo(name = "addedAt")
    val addedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "isTreeUri")
    val isTreeUri: Boolean = false,

    @ColumnInfo(name = "status")
    val status: String = "PENDING" // "PENDING", "INDEXED", "FAILED"
)
