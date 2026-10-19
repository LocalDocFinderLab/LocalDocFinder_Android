package com.example

import com.example.engine.ShareFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareFormatterTest {

    @Test
    fun shortTextIsKeptAndWhitespaceCollapsed() {
        assertEquals("one two three", ShareFormatter.excerpt("one\n two\t three ", emptyList()))
    }

    @Test
    fun longTextIsCutAtAWordBoundaryWithEllipsis() {
        val text = (1..200).joinToString(" ") { "word$it" }
        val excerpt = ShareFormatter.excerpt(text, emptyList(), maxChars = 100)
        assertTrue(excerpt.endsWith("…"))
        assertTrue(excerpt.length <= 102)
        assertFalse(excerpt.dropLast(1).endsWith("wor")) // never ends mid-word
    }

    @Test
    fun windowIsCentredOnTheFirstSearchTerm() {
        val filler = "lorem ipsum ".repeat(100)
        val excerpt = ShareFormatter.excerpt(filler + "NEEDLE appears here " + filler, listOf("needle"), maxChars = 120)
        assertTrue(excerpt, excerpt.contains("NEEDLE"))
        assertTrue(excerpt.startsWith("…") && excerpt.endsWith("…"))
    }

    @Test
    fun formatIncludesSourceAndPage() {
        val body = ShareFormatter.format("Lease.pdf", "p. 4", "Rent is due on the 1st.", emptyList())
        assertTrue(body.contains("“Rent is due on the 1st.”"))
        assertTrue(body.contains("— Lease.pdf, p. 4"))
        assertTrue(ShareFormatter.format("Lease.pdf", null, "x", emptyList()).contains("— Lease.pdf\n"))
    }
}
