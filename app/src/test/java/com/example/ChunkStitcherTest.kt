package com.example

import com.example.engine.ChunkStitcher
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkStitcherTest {

    @Test
    fun trimsOverlapRepeatedFromPreviousChunk() {
        val overlap = "the quick brown fox jumps over the lazy dog"
        val first = "Intro sentence about animals. $overlap"
        val second = "$overlap and then keeps running far away."

        val stitched = ChunkStitcher.stitch(listOf(first, second))

        assertEquals(first, stitched[0])
        assertEquals("and then keeps running far away.", stitched[1])
    }

    @Test
    fun keepsChunksWithoutOverlapUnchanged() {
        val chunks = listOf("Completely different first chunk of text.", "Another unrelated paragraph altogether.")
        assertEquals(chunks, ChunkStitcher.stitch(chunks))
    }

    @Test
    fun handlesEmptyAndSingleChunkInput() {
        assertEquals(emptyList<String>(), ChunkStitcher.stitch(emptyList()))
        assertEquals(listOf("only"), ChunkStitcher.stitch(listOf("only")))
    }

    @Test
    fun toleratesTrailingWhitespaceOnPreviousChunk() {
        val overlap = "shared words across two neighbouring chunks"
        val stitched = ChunkStitcher.stitch(listOf("Start. $overlap   \n", "$overlap continues here."))
        assertEquals("continues here.", stitched[1])
    }
}
