package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full-Text Search (FTS4) virtual table over [DocumentChunkEntity] for fast keyword indexing
 * across chunk text, file names, URIs and tags.
 *
 * This is an *external-content* table (`content=documents`): the index stores only the inverted
 * index, not a second copy of the text, and its `rowid` is the `documents.id` of the chunk.
 * Room generates the INSERT/UPDATE/DELETE triggers that keep the index in sync, so callers
 * never write to this table directly. Uses the unicode61 tokenizer for multilingual,
 * punctuation-aware tokenization.
 */
@Entity(tableName = "documents_fts")
@Fts4(contentEntity = DocumentChunkEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class DocumentChunkFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long,
    val chunkText: String,
    val fileName: String,
    val fileUri: String,
    val tags: String
)
