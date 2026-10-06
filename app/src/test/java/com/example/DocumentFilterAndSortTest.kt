package com.example

import com.example.engine.ConfidenceDistribution
import com.example.engine.DateRangePreset
import com.example.engine.SearchFilterState
import com.example.engine.SearchResult
import com.example.engine.SearchSortOrder
import com.example.engine.calculateDateRangeBounds
import com.example.engine.extractTopKConfidenceScores
import com.example.engine.formatDateRangeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentFilterAndSortTest {

    private fun createDummyResult(
        id: Long,
        fileName: String,
        cosineSim: Float,
        rrfScore: Float,
        timestamp: Long,
        fileSize: Long = 0L
    ): SearchResult {
        return SearchResult(
            chunkId = id,
            fileUri = "file:///storage/emulated/0/Documents/$fileName",
            fileName = fileName,
            chunkIndex = 0,
            chunkText = "Sample text content for $fileName",
            snippet = "Sample snippet for $fileName",
            highlightedTerms = listOf("sample"),
            cosineSimilarity = cosineSim,
            vectorRank = 1,
            ftsRank = 1,
            rrfScore = rrfScore,
            latencyMs = 12L,
            tags = listOf("test"),
            timestamp = timestamp,
            fileSize = fileSize
        )
    }

    @Test
    fun testFileExtensionExtraction() {
        val pdfResult = createDummyResult(1, "Deep_Learning_Survey.pdf", 0.92f, 0.033f, 1000L)
        val txtResult = createDummyResult(2, "Notes.TXT", 0.85f, 0.028f, 2000L)
        val mdResult = createDummyResult(3, "README.markdown", 0.75f, 0.025f, 3000L)
        val docxResult = createDummyResult(4, "Proposal.docx", 0.65f, 0.020f, 4000L)

        assertEquals("pdf", pdfResult.fileExtension)
        assertEquals("txt", txtResult.fileExtension)
        assertEquals("markdown", mdResult.fileExtension)
        assertEquals("docx", docxResult.fileExtension)
    }

    @Test
    fun testFilterByFileType() {
        val results = listOf(
            createDummyResult(1, "Neural_Architecture.pdf", 0.95f, 0.035f, 1000L),
            createDummyResult(2, "Hardware_Acceleration.txt", 0.88f, 0.030f, 2000L),
            createDummyResult(3, "Transformer_Attention.pdf", 0.82f, 0.027f, 3000L),
            createDummyResult(4, "Quantization_Notes.md", 0.78f, 0.024f, 4000L),
            createDummyResult(5, "Dataset_Metrics.docx", 0.60f, 0.019f, 5000L)
        )

        // Filter PDF
        val pdfFilter = SearchFilterState(selectedFileType = "pdf")
        val pdfResults = pdfFilter.apply(results)
        assertEquals(2, pdfResults.size)
        assertTrue(pdfResults.all { it.fileExtension == "pdf" })

        // Filter TXT
        val txtFilter = SearchFilterState(selectedFileType = "txt")
        val txtResults = txtFilter.apply(results)
        assertEquals(1, txtResults.size)
        assertEquals("Hardware_Acceleration.txt", txtResults[0].fileName)

        // Filter Markdown
        val mdFilter = SearchFilterState(selectedFileType = "md")
        val mdResults = mdFilter.apply(results)
        assertEquals(1, mdResults.size)
        assertEquals("Quantization_Notes.md", mdResults[0].fileName)

        // Filter All (null or "All")
        val allFilter = SearchFilterState(selectedFileType = null)
        val allResults = allFilter.apply(results)
        assertEquals(5, allResults.size)
    }

    @Test
    fun testSortByRelevance() {
        val results = listOf(
            createDummyResult(1, "Low_Relevance.txt", 0.40f, 0.010f, 5000L),
            createDummyResult(2, "Highest_Relevance.pdf", 0.95f, 0.035f, 1000L),
            createDummyResult(3, "Medium_Relevance.md", 0.70f, 0.025f, 3000L)
        )

        val sortRelevance = SearchFilterState(sortOrder = SearchSortOrder.RELEVANCE)
        val sorted = sortRelevance.apply(results)

        assertEquals("Highest_Relevance.pdf", sorted[0].fileName)
        assertEquals("Medium_Relevance.md", sorted[1].fileName)
        assertEquals("Low_Relevance.txt", sorted[2].fileName)
    }

    @Test
    fun testSortByDateIndexed() {
        val results = listOf(
            createDummyResult(1, "Oldest_Doc.pdf", 0.90f, 0.030f, 1000L),
            createDummyResult(2, "Middle_Doc.txt", 0.90f, 0.030f, 5000L),
            createDummyResult(3, "Newest_Doc.md", 0.90f, 0.030f, 9000L)
        )

        // Newest First (Descending)
        val sortNewest = SearchFilterState(sortOrder = SearchSortOrder.DATE_DESC)
        val sortedNewest = sortNewest.apply(results)
        assertEquals("Newest_Doc.md", sortedNewest[0].fileName)
        assertEquals("Middle_Doc.txt", sortedNewest[1].fileName)
        assertEquals("Oldest_Doc.pdf", sortedNewest[2].fileName)

        // Oldest First (Ascending)
        val sortOldest = SearchFilterState(sortOrder = SearchSortOrder.DATE_ASC)
        val sortedOldest = sortOldest.apply(results)
        assertEquals("Oldest_Doc.pdf", sortedOldest[0].fileName)
        assertEquals("Middle_Doc.txt", sortedOldest[1].fileName)
        assertEquals("Newest_Doc.md", sortedOldest[2].fileName)
    }

    @Test
    fun testSortByFileSize() {
        val results = listOf(
            createDummyResult(1, "Small_Doc.txt", 0.90f, 0.030f, 1000L, fileSize = 500L),
            createDummyResult(2, "Huge_Doc.pdf", 0.90f, 0.030f, 2000L, fileSize = 50000L),
            createDummyResult(3, "Medium_Doc.md", 0.90f, 0.030f, 3000L, fileSize = 5000L)
        )

        // Largest first (Descending)
        val sortSize = SearchFilterState(sortOrder = SearchSortOrder.FILE_SIZE)
        val sorted = sortSize.apply(results)
        assertEquals("Huge_Doc.pdf", sorted[0].fileName)
        assertEquals("Medium_Doc.md", sorted[1].fileName)
        assertEquals("Small_Doc.txt", sorted[2].fileName)
    }

    @Test
    fun testSortByFileName() {
        val results = listOf(
            createDummyResult(1, "Zebra_Neural.txt", 0.90f, 0.030f, 1000L),
            createDummyResult(2, "Alpha_Tensor.pdf", 0.90f, 0.030f, 2000L),
            createDummyResult(3, "Gamma_FTS.md", 0.90f, 0.030f, 3000L)
        )

        // A-Z
        val sortAsc = SearchFilterState(sortOrder = SearchSortOrder.NAME_ASC)
        val sortedAsc = sortAsc.apply(results)
        assertEquals("Alpha_Tensor.pdf", sortedAsc[0].fileName)
        assertEquals("Gamma_FTS.md", sortedAsc[1].fileName)
        assertEquals("Zebra_Neural.txt", sortedAsc[2].fileName)

        // Z-A
        val sortDesc = SearchFilterState(sortOrder = SearchSortOrder.NAME_DESC)
        val sortedDesc = sortDesc.apply(results)
        assertEquals("Zebra_Neural.txt", sortedDesc[0].fileName)
        assertEquals("Gamma_FTS.md", sortedDesc[1].fileName)
        assertEquals("Alpha_Tensor.pdf", sortedDesc[2].fileName)
    }

    @Test
    fun testCombinedFilterAndSort() {
        val results = listOf(
            createDummyResult(1, "Alpha_Notes.txt", 0.80f, 0.025f, 1000L),
            createDummyResult(2, "Beta_Model.pdf", 0.95f, 0.035f, 2000L),
            createDummyResult(3, "Delta_Analysis.pdf", 0.90f, 0.030f, 5000L),
            createDummyResult(4, "Charlie_Summary.pdf", 0.85f, 0.028f, 3000L)
        )

        // Filter PDF only AND sort by File Name (A to Z)
        val filterAndSort = SearchFilterState(
            selectedFileType = "pdf",
            sortOrder = SearchSortOrder.NAME_ASC
        )
        val output = filterAndSort.apply(results)

        assertEquals(3, output.size)
        assertEquals("Beta_Model.pdf", output[0].fileName)
        assertEquals("Charlie_Summary.pdf", output[1].fileName)
        assertEquals("Delta_Analysis.pdf", output[2].fileName)
    }

    @Test
    fun testConfidenceDistributionCalculation() {
        val results = listOf(
            createDummyResult(1, "Doc1.pdf", 0.95f, 0.035f, 1000L), // Bin 4 (80-100%) - High
            createDummyResult(2, "Doc2.pdf", 0.85f, 0.030f, 2000L), // Bin 4 (80-100%) - High
            createDummyResult(3, "Doc3.txt", 0.72f, 0.026f, 3000L), // Bin 3 (60-80%) - High
            createDummyResult(4, "Doc4.md", 0.65f, 0.022f, 4000L),  // Bin 3 (60-80%) - Moderate
            createDummyResult(5, "Doc5.docx", 0.45f, 0.018f, 5000L),// Bin 2 (40-60%) - Moderate
            createDummyResult(6, "Doc6.txt", 0.25f, 0.012f, 6000L), // Bin 1 (20-40%) - Low
            createDummyResult(7, "Doc7.txt", 0.10f, 0.008f, 7000L)  // Bin 0 (0-20%) - Low
        )

        val dist = ConfidenceDistribution.fromResults(results)

        assertEquals(7, dist.totalCount)
        assertEquals(3, dist.highConfidenceCount) // 0.95, 0.85, 0.72
        assertEquals(2, dist.moderateConfidenceCount) // 0.65, 0.45
        assertEquals(2, dist.lowConfidenceCount) // 0.25, 0.10

        assertEquals(5, dist.bins.size)
        // Bin 0: 0-20%
        assertEquals(1, dist.bins[0].count)
        // Bin 1: 20-40%
        assertEquals(1, dist.bins[1].count)
        // Bin 2: 40-60%
        assertEquals(1, dist.bins[2].count)
        // Bin 3: 60-80%
        assertEquals(2, dist.bins[3].count)
        // Bin 4: 80-100%
        assertEquals(2, dist.bins[4].count)

        assertTrue(dist.maxScore >= 0.95f)
        assertTrue(dist.minScore <= 0.10f)
        assertTrue(dist.averageScore > 0.50f)
    }

    @Test
    fun testEmptyResultsDistribution() {
        val emptyDist = ConfidenceDistribution.fromResults(emptyList())
        assertEquals(0, emptyDist.totalCount)
        assertEquals(5, emptyDist.bins.size)
        assertTrue(emptyDist.bins.all { it.count == 0 })
    }

    @Test
    fun testFilterByDateRange() {
        val results = listOf(
            createDummyResult(1, "Jan_Doc.pdf", 0.90f, 0.03f, 100_000L),
            createDummyResult(2, "Feb_Doc.txt", 0.85f, 0.02f, 200_000L),
            createDummyResult(3, "Mar_Doc.md", 0.80f, 0.02f, 300_000L),
            createDummyResult(4, "Apr_Doc.pdf", 0.75f, 0.01f, 400_000L),
            createDummyResult(5, "May_Doc.docx", 0.70f, 0.01f, 500_000L)
        )

        // Filter from 200_000 to 400_000
        val rangeFilter = SearchFilterState(
            startDateMillis = 200_000L,
            endDateMillis = 400_000L
        )
        val filtered = rangeFilter.apply(results)
        assertEquals(3, filtered.size)
        assertEquals("Feb_Doc.txt", filtered[0].fileName)
        assertEquals("Mar_Doc.md", filtered[1].fileName)
        assertEquals("Apr_Doc.pdf", filtered[2].fileName)

        // Filter only startDate (>= 350_000)
        val startOnlyFilter = SearchFilterState(startDateMillis = 350_000L)
        val startFiltered = startOnlyFilter.apply(results)
        assertEquals(2, startFiltered.size)
        assertEquals("Apr_Doc.pdf", startFiltered[0].fileName)
        assertEquals("May_Doc.docx", startFiltered[1].fileName)

        // Filter only endDate (<= 250_000)
        val endOnlyFilter = SearchFilterState(endDateMillis = 250_000L)
        val endFiltered = endOnlyFilter.apply(results)
        assertEquals(2, endFiltered.size)
        assertEquals("Jan_Doc.pdf", endFiltered[0].fileName)
        assertEquals("Feb_Doc.txt", endFiltered[1].fileName)
    }

    @Test
    fun testDateRangePresetsCalculation() {
        val now = 1_700_000_000_000L

        // All Time
        val (allStart, allEnd) = calculateDateRangeBounds(DateRangePreset.ALL_TIME, now)
        assertEquals(null, allStart)
        assertEquals(null, allEnd)

        // Past 7 Days
        val (p7Start, p7End) = calculateDateRangeBounds(DateRangePreset.PAST_7_DAYS, now)
        assertNotNull(p7Start)
        assertNotNull(p7End)
        assertEquals(7L * 24 * 60 * 60 * 1000L, now - p7Start!!)

        // Past 30 Days
        val (p30Start, p30End) = calculateDateRangeBounds(DateRangePreset.PAST_30_DAYS, now)
        assertNotNull(p30Start)
        assertEquals(30L * 24 * 60 * 60 * 1000L, now - p30Start!!)

        // Past 90 Days
        val (p90Start, p90End) = calculateDateRangeBounds(DateRangePreset.PAST_90_DAYS, now)
        assertNotNull(p90Start)
        assertEquals(90L * 24 * 60 * 60 * 1000L, now - p90Start!!)
    }

    @Test
    fun testCombinedFileTypeDateRangeAndSort() {
        val results = listOf(
            createDummyResult(1, "Old_Model.pdf", 0.95f, 0.04f, 1000L),
            createDummyResult(2, "Recent_Notes.txt", 0.90f, 0.03f, 5000L),
            createDummyResult(3, "Recent_Model.pdf", 0.85f, 0.02f, 6000L),
            createDummyResult(4, "Future_Model.pdf", 0.80f, 0.01f, 9000L)
        )

        // Filter: PDF files created between 2000L and 8000L, sorted by Date Descending
        val state = SearchFilterState(
            selectedFileType = "pdf",
            startDateMillis = 2000L,
            endDateMillis = 8000L,
            sortOrder = SearchSortOrder.DATE_DESC
        )
        val filtered = state.apply(results)
        assertEquals(1, filtered.size)
        assertEquals("Recent_Model.pdf", filtered[0].fileName)
    }

    @Test
    fun testExtractTopKConfidenceScores() {
        val results = listOf(
            createDummyResult(1, "DocA.pdf", 0.95f, 0.035f, 1000L),
            createDummyResult(2, "DocB.txt", 0.88f, 0.030f, 2000L),
            createDummyResult(3, "DocC.md", 0.65f, 0.025f, 3000L),
            createDummyResult(4, "DocD.docx", 0.35f, 0.015f, 4000L),
            createDummyResult(5, "DocE.pdf", 0.20f, 0.010f, 5000L)
        )

        val top3 = extractTopKConfidenceScores(results, k = 3)
        assertEquals(3, top3.size)

        assertEquals(1, top3[0].rank)
        assertEquals("DocA.pdf", top3[0].fileName)
        assertEquals(95.0f, top3[0].scorePercentage, 0.01f)
        assertEquals("HIGH", top3[0].tier)
        assertEquals("#10B981", top3[0].tierColorHex)

        assertEquals(2, top3[1].rank)
        assertEquals("DocB.txt", top3[1].fileName)
        assertEquals(88.0f, top3[1].scorePercentage, 0.01f)
        assertEquals("HIGH", top3[1].tier)

        assertEquals(3, top3[2].rank)
        assertEquals("DocC.md", top3[2].fileName)
        assertEquals(65.0f, top3[2].scorePercentage, 0.01f)
        assertEquals("MODERATE", top3[2].tier)
        assertEquals("#F59E0B", top3[2].tierColorHex)

        val topAll = extractTopKConfidenceScores(results, k = 10)
        assertEquals(5, topAll.size)
        assertEquals("LOW", topAll[3].tier)
        assertEquals("#EF4444", topAll[3].tierColorHex)
    }

    @Test
    fun testHybridRankingAlgorithmBM25AndCosineSim() {
        val r1 = createDummyResult(1, "Exact_Keyword_Doc.txt", cosineSim = 0.50f, rrfScore = 0.02f, timestamp = 1000L).copy(bm25Score = 0.95f)
        val r2 = createDummyResult(2, "Semantic_Vector_Doc.pdf", cosineSim = 0.95f, rrfScore = 0.03f, timestamp = 2000L).copy(bm25Score = 0.20f)
        val r3 = createDummyResult(3, "Balanced_Hybrid_Doc.md", cosineSim = 0.85f, rrfScore = 0.035f, timestamp = 3000L).copy(bm25Score = 0.85f)

        val rawList = listOf(r1, r2, r3)

        // Balanced 50/50 weights:
        // r1 combined = 0.5 * 0.50 + 0.5 * 0.95 = 0.725
        // r2 combined = 0.5 * 0.92 + 0.5 * 0.20 = 0.560
        // r3 combined = 0.5 * 0.85 + 0.5 * 0.85 = 0.850
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = com.example.ui.MainViewModel(context as android.app.Application)

        val rankedBalanced = vm.calculateHybridRanking(rawList, vectorW = 0.5f, bm25W = 0.5f, mode = com.example.engine.SearchMode.HYBRID)
        assertEquals(3, rankedBalanced.size)

        val sorted = SearchFilterState(sortOrder = SearchSortOrder.RELEVANCE).apply(rankedBalanced)
        assertEquals("Balanced_Hybrid_Doc.md", sorted[0].fileName)
        assertEquals("Exact_Keyword_Doc.txt", sorted[1].fileName)
        assertEquals("Semantic_Vector_Doc.pdf", sorted[2].fileName)

        // Vector-heavy 90% Vector / 10% BM25 weights:
        val rankedVectorHeavy = vm.calculateHybridRanking(rawList, vectorW = 0.9f, bm25W = 0.1f, mode = com.example.engine.SearchMode.HYBRID)
        val sortedVec = SearchFilterState(sortOrder = SearchSortOrder.RELEVANCE).apply(rankedVectorHeavy)
        assertEquals("Semantic_Vector_Doc.pdf", sortedVec[0].fileName)
    }
}
