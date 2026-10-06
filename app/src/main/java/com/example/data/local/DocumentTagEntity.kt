package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a category/tag assigned to a document identified by its [fileUri].
 */
@Entity(
    tableName = "document_tags",
    indices = [
        Index(value = ["fileUri"]),
        Index(value = ["tag"]),
        Index(value = ["fileUri", "tag"], unique = true)
    ]
)
data class DocumentTagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileUri: String,
    val tag: String
)
