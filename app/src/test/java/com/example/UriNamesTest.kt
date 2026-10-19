package com.example

import com.example.engine.UriNames
import org.junit.Assert.assertEquals
import org.junit.Test

class UriNamesTest {
    private val saf = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FTax%20Return.pdf"

    @Test
    fun nameIsTheLastPathSegmentOfASafUri() {
        assertEquals("Tax Return.pdf", UriNames.displayName(saf))
        assertEquals("primary:Download", UriNames.location(saf))
    }

    @Test
    fun plainFileUrisWork() {
        assertEquals("a.txt", UriNames.displayName("file:///storage/emulated/0/Docs/a.txt"))
        assertEquals("file:///storage/emulated/0/Docs", UriNames.location("file:///storage/emulated/0/Docs/a.txt"))
    }

    @Test
    fun malformedEscapesFallBackToTheRawText() {
        assertEquals("100%.txt", UriNames.displayName("file:///x/100%.txt"))
    }
}
