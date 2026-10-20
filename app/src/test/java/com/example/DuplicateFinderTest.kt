package com.example

import com.example.engine.DuplicateFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private fun file(uri: String, vararg prints: Long) = prints.map { uri to it }

    @Test
    fun identicalFilesFormAnExactGroup() {
        val groups = DuplicateFinder.find(
            file("a.pdf", 1, 2, 3) + file("copy of a.pdf", 1, 2, 3) + file("other.pdf", 9, 8)
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("a.pdf", "copy of a.pdf"), groups[0].fileUris)
        assertTrue(groups[0].exact)
        assertEquals(1f, groups[0].similarity, 0f)
    }

    @Test
    fun mostlyOverlappingFilesAreNearDuplicates() {
        // 9 shared chunks, one extra in each: Jaccard = 9 / 11 = 0.82
        val shared = (1L..9L).toList().toLongArray()
        val groups = DuplicateFinder.find(
            file("v1.docx", *shared, 100) + file("v2.docx", *shared, 200)
        )
        assertEquals(1, groups.size)
        assertFalse(groups[0].exact)
        assertTrue(groups[0].similarity in 0.8f..0.9f)
    }

    @Test
    fun weakOverlapIsNotADuplicate() {
        val groups = DuplicateFinder.find(file("a", 1, 2, 3, 4) + file("b", 4, 5, 6, 7))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun chainsOfDuplicatesMergeIntoOneGroupAndExactComesFirst() {
        val groups = DuplicateFinder.find(
            file("x1", 1, 2) + file("x2", 1, 2) + file("x3", 1, 2) +
                file("y1", 10, 11, 12, 13, 14, 15, 16, 17, 18, 19) + file("y2", 10, 11, 12, 13, 14, 15, 16, 17, 18, 20)
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("x1", "x2", "x3"), groups[0].fileUris)
        assertTrue(groups[0].exact)
        assertEquals(listOf("y1", "y2"), groups[1].fileUris)
    }

    @Test
    fun boilerplateSharedByManyFilesIsIgnored() {
        val chunks = (1..60).flatMap { file("doc$it", 777L, 1000L + it) }
        assertTrue(DuplicateFinder.find(chunks).isEmpty())
    }

    @Test
    fun repeatedChunksInOneFileCountOnce() {
        val groups = DuplicateFinder.find(file("a", 1, 1, 1, 2) + file("b", 1, 2))
        assertEquals(1, groups.size)
        assertTrue(groups[0].exact)
    }

    @Test
    fun fingerprintIsStableAndSensitive() {
        val a = DuplicateFinder.fingerprint(byteArrayOf(1, 2, 3))
        assertEquals(a, DuplicateFinder.fingerprint(byteArrayOf(1, 2, 3)))
        assertTrue(a != DuplicateFinder.fingerprint(byteArrayOf(1, 2, 4)))
    }
}
