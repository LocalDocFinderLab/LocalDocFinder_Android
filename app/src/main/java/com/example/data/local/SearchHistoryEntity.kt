package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["query"])
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val query: String,
    val searchMode: String, // "HYBRID", "VECTOR", "KEYWORD"
    val resultCount: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val filterTag: String? = null
)
