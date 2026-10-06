package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full-Text Search (FTS4) virtual table entity for fast BM25/keyword indexing
 * across document text, titles, and paths.
 * Uses unicode61 tokenizer for full multilingual and punctuation-aware tokenization.
 */
@Entity(tableName = "documents_fts")
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class DocumentChunkFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long = 0,
    val chunkId: Long,
    val chunkText: String,
    val fileName: String,
    val fileUri: String = "",
    val tags: String = ""
)

