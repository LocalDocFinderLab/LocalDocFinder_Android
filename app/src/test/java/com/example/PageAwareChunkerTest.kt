package com.example

import com.example.engine.extraction.ChunkMetadata
import com.example.engine.extraction.ExtractedPage
import com.example.engine.extraction.PageAwareChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAwareChunkerTest {

    private val passthrough: (String) -> List<String> = { listOf(it) }

    @Test
    fun `short pages are merged into one chunk spanning the page range`() {
        val pages = listOf(
            ExtractedPage(1, "Alpha."),
            ExtractedPage(2, "Beta."),
            ExtractedPage(3, "Gamma.")
        )
        val chunks = PageAwareChunker.chunk(pages, targetChars = 100, splitPage = passthrough)
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].page)
        assertEquals(3, chunks[0].pageEnd)
        assertEquals("Alpha.\n\nBeta.\n\nGamma.", chunks[0].text)
    }

    @Test
    fun `a page that does not fit starts a new chunk and keeps its own page number`() {
        val pages = listOf(
            ExtractedPage(1, "a".repeat(60)),
            ExtractedPage(2, "b".repeat(60)),
            ExtractedPage(3, "c".repeat(10))
        )
        val chunks = PageAwareChunker.chunk(pages, targetChars = 100, splitPage = passthrough)
        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].page); assertEquals(1, chunks[0].pageEnd)
        // page 3 is small enough to join page 2
        assertEquals(2, chunks[1].page); assertEquals(3, chunks[1].pageEnd)
    }

    @Test
    fun `pieces of one long page all report that page`() {
        val pages = listOf(ExtractedPage(7, "x"))
        val chunks = PageAwareChunker.chunk(pages, targetChars = 50) {
            listOf("p".repeat(45), "q".repeat(45), "r".repeat(45))
        }
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.page == 7 && it.pageEnd == 7 })
    }

    @Test
    fun `blank pieces and empty input produce no chunks`() {
        assertTrue(PageAwareChunker.chunk(emptyList(), 100, passthrough).isEmpty())
        val chunks = PageAwareChunker.chunk(listOf(ExtractedPage(1, "   ")), 100, passthrough)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `chunk metadata round trips page and page range`() {
        assertEquals("", ChunkMetadata.forPages(null, null))
        assertEquals("page=3", ChunkMetadata.forPages(3, 3))
        assertEquals("page=3", ChunkMetadata.forPages(3, null))
        assertEquals("page=3;pageEnd=5", ChunkMetadata.forPages(3, 5))

        assertEquals(3, ChunkMetadata.page("page=3;pageEnd=5"))
        assertEquals(5, ChunkMetadata.pageEnd("page=3;pageEnd=5"))
        assertNull(ChunkMetadata.pageEnd("page=3"))
        assertNull(ChunkMetadata.page(""))
        assertNull(ChunkMetadata.page("something=else"))

        assertEquals("p. 3", ChunkMetadata.pageLabel("model=bge_small_en_v15;page=3"))
        assertEquals("pp. 3–5", ChunkMetadata.pageLabel("page=3;pageEnd=5"))
        assertNull(ChunkMetadata.pageLabel(""))
    }

    @Test
    fun `chunk metadata carries the embedding model id next to the page`() {
        assertEquals("model=bge_small_en_v15", ChunkMetadata.encode(modelId = "bge_small_en_v15"))
        assertEquals("model=m;page=3;pageEnd=4", ChunkMetadata.encode(3, 4, "m"))
        assertEquals("m", ChunkMetadata.model("model=m;page=3"))
        assertNull(ChunkMetadata.model("page=3"))
        assertNull(ChunkMetadata.model(""))

        // Re-indexing with another model swaps the id and keeps the page info.
        assertEquals("model=new;page=3;pageEnd=4", ChunkMetadata.withModel("model=old;page=3;pageEnd=4", "new"))
        assertEquals("model=new", ChunkMetadata.withModel("", "new"))
        assertEquals("model=new;page=7", ChunkMetadata.withModel("page=7", "new"))
    }
}
