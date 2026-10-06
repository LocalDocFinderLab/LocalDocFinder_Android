package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the external-content FTS4 index stays in sync with `documents` via Room's triggers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomFtsExternalContentTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun chunk(uri: String, text: String, hash: String, tags: String = "") =
        DocumentChunkEntity(
            fileUri = uri, fileName = uri.substringAfterLast('/'), chunkIndex = 0,
            chunkText = text, hash = hash, timestamp = 1L,
            embedding = FloatArray(4) { 0.5f }, tags = tags
        )

    @Test
    fun insertIsIndexedAndRowidMatchesChunkId() = runBlocking {
        val dao = database.documentChunkDao()
        dao.insertChunksWithFts(listOf(chunk("file:///a.txt", "quantized embeddings on device", "h1")))

        val ids = dao.searchFtsChunkIds("quantized*")
        val stored = dao.getChunksForFile("file:///a.txt").single()
        assertEquals(listOf(stored.id), ids)
        assertEquals(listOf(stored.id), dao.searchFts("embeddings").map { it.id })
    }

    @Test
    fun tagUpdateIsReflectedInIndex() = runBlocking {
        val dao = database.documentChunkDao()
        dao.insertChunksWithFts(listOf(chunk("file:///b.txt", "plain body text", "h2")))
        assertTrue(dao.searchFts("invoice*").isEmpty())

        dao.setTagsForFile("file:///b.txt", listOf("invoice"))
        assertEquals(1, dao.searchFts("invoice*").size)

        dao.setTagsForFile("file:///b.txt", emptyList())
        assertTrue(dao.searchFts("invoice*").isEmpty())
    }

    @Test
    fun deleteAndClearRemoveIndexEntries() = runBlocking {
        val dao = database.documentChunkDao()
        dao.insertChunksWithFts(
            listOf(
                chunk("file:///c.txt", "alpha searchable", "h3"),
                chunk("file:///d.txt", "beta searchable", "h4")
            )
        )
        assertEquals(2, dao.searchFts("searchable").size)

        dao.deleteFileRecord("file:///c.txt")
        assertEquals(1, dao.searchFts("searchable").size)
        assertTrue(dao.searchFts("alpha").isEmpty())

        dao.clearAll()
        assertTrue(dao.searchFts("searchable").isEmpty())
    }

    @Test
    fun rebuildAndOptimizeKeepIndexQueryable() = runBlocking {
        val dao = database.documentChunkDao()
        dao.insertChunksWithFts(listOf(chunk("file:///e.txt", "gamma delta", "h5")))
        database.rebuildFtsIndex()
        database.optimizeFtsIndex()
        assertEquals(1, dao.searchFts("gamma").size)
    }
}
