package com.example

import com.example.engine.DocumentLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLabelsTest {

    @Test
    fun merge_withNoTagsInUse_returnsPredefinedLabelsInOrder() {
        assertEquals(DocumentLabels.PREDEFINED, DocumentLabels.merge(emptyList()))
    }

    @Test
    fun merge_appendsCustomTagsAfterPredefinedOnes() {
        val merged = DocumentLabels.merge(listOf("ProjectX", "Zeta"))
        assertEquals(DocumentLabels.PREDEFINED + listOf("ProjectX", "Zeta"), merged)
    }

    @Test
    fun merge_dropsCaseInsensitiveDuplicatesAndBlanks() {
        val merged = DocumentLabels.merge(listOf("work", "  ", "FINANCE", "Custom", "custom"))
        assertEquals(1, merged.count { it.equals("work", ignoreCase = true) })
        assertEquals(1, merged.count { it.equals("finance", ignoreCase = true) })
        assertEquals(1, merged.count { it.equals("custom", ignoreCase = true) })
        assertFalse(merged.any { it.isBlank() })
        // The predefined spelling wins over the one found in the database.
        assertTrue(merged.contains("Work"))
        assertTrue(merged.contains("Finance"))
    }

    @Test
    fun isPredefined_ignoresCaseAndWhitespace() {
        assertTrue(DocumentLabels.isPredefined(" legal "))
        assertFalse(DocumentLabels.isPredefined("Gardening"))
    }
}
