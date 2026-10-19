package com.example

import com.example.engine.SearchSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionsTest {

    @Test
    fun shortInputGivesNothing() {
        assertTrue(SearchSuggestions.build("t", listOf("tax"), listOf("tax.pdf")).isEmpty())
        assertTrue(SearchSuggestions.build("   ", listOf("tax"), emptyList()).isEmpty())
    }

    @Test
    fun historyPrefixMatchesComeFirstInRecencyOrder() {
        val result = SearchSuggestions.build(
            "tax",
            history = listOf("tax return 2024", "state tax", "taxi receipt"),
            fileNames = listOf("Tax_Summary.pdf")
        )
        assertEquals(listOf("tax return 2024", "taxi receipt", "state tax", "Tax Summary"), result)
    }

    @Test
    fun fileNamesAreCleanedAndDeduplicated() {
        val result = SearchSuggestions.build(
            "lease",
            history = emptyList(),
            fileNames = listOf("Lease-Agreement_2024.pdf", "lease agreement 2024.docx", "old_lease.txt")
        )
        assertEquals(listOf("Lease Agreement 2024", "old lease"), result)
    }

    @Test
    fun theTypedTextItselfIsNeverSuggested() {
        val result = SearchSuggestions.build("Invoice", listOf("invoice", "INVOICE 12"), emptyList())
        assertEquals(listOf("INVOICE 12"), result)
    }

    @Test
    fun matchesTheStartOfAnyWordAndRespectsLimit() {
        val history = (1..10).map { "report number $it" } + "quarterly report"
        val result = SearchSuggestions.build("rep", history, emptyList(), limit = 3)
        assertEquals(3, result.size)
        assertEquals("report number 1", result[0])
        val all = SearchSuggestions.build("rep", listOf("quarterly report"), emptyList())
        assertEquals(listOf("quarterly report"), all)
    }
}
