package com.example.engine

/**
 * Rebuilds a readable document from the overlapping chunks stored in the index.
 *
 * The chunker repeats the last ~150 characters of each chunk at the start of the next one so no
 * sentence is cut off for embedding. That overlap is useful for search but would show duplicated
 * text when chunks are displayed one after another, so it is trimmed away here.
 */
object ChunkStitcher {

    private const val MAX_OVERLAP = 220
    private const val MIN_OVERLAP = 12

    /**
     * Returns one entry per input chunk (same order and size) with the text that repeats the end of the
     * preceding chunk removed. A chunk with no detectable overlap is returned unchanged.
     */
    fun stitch(chunks: List<String>): List<String> {
        if (chunks.size < 2) return chunks
        val result = ArrayList<String>(chunks.size)
        result.add(chunks[0])
        for (i in 1 until chunks.size) {
            val previous = chunks[i - 1].trimEnd()
            val current = chunks[i]
            val overlap = overlapLength(previous, current)
            result.add(if (overlap > 0) current.substring(overlap).trimStart() else current)
        }
        return result
    }

    /** Length of the longest prefix of [current] that is also a suffix of [previous]; 0 if too short to be real overlap. */
    private fun overlapLength(previous: String, current: String): Int {
        val max = minOf(MAX_OVERLAP, previous.length, current.length)
        var k = max
        while (k >= MIN_OVERLAP) {
            if (previous.endsWith(current.substring(0, k))) return k
            k--
        }
        return 0
    }
}
