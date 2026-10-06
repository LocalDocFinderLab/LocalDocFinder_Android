package com.example.engine

import java.util.Locale

/**
 * Defines available sorting options for semantic, hybrid, and keyword search results.
 */
enum class SearchSortOrder(val displayName: String, val shortName: String) {
    RELEVANCE("Relevance (RRF/Cosine)", "Relevance"),
    DATE_DESC("Date Modified (Newest)", "Date Modified"),
    DATE_ASC("Date Modified (Oldest)", "Date ↑"),
    FILE_SIZE("File Size (Largest)", "File Size"),
    NAME_ASC("File Name (A to Z)", "Name A-Z"),
    NAME_DESC("File Name (Z to A)", "Name Z-A")
}

/**
 * Individual bucket/bin for semantic confidence score distribution histogram.
 */
data class ScoreBin(
    val label: String,
    val minScore: Float,
    val maxScore: Float,
    val count: Int,
    val percentage: Float
)

/**
 * Aggregated statistics and binned distribution of semantic search match confidence scores.
 */
data class ConfidenceDistribution(
    val bins: List<ScoreBin>,
    val averageScore: Float,
    val maxScore: Float,
    val minScore: Float,
    val totalCount: Int,
    val highConfidenceCount: Int,
    val moderateConfidenceCount: Int,
    val lowConfidenceCount: Int,
    val qualityAssessment: String
) {
    companion object {
        val EMPTY = ConfidenceDistribution(
            bins = listOf(
                ScoreBin("0-20%", 0.0f, 0.2f, 0, 0f),
                ScoreBin("20-40%", 0.2f, 0.4f, 0, 0f),
                ScoreBin("40-60%", 0.4f, 0.6f, 0, 0f),
                ScoreBin("60-80%", 0.6f, 0.8f, 0, 0f),
                ScoreBin("80-100%", 0.8f, 1.0f, 0, 0f)
            ),
            averageScore = 0f,
            maxScore = 0f,
            minScore = 0f,
            totalCount = 0,
            highConfidenceCount = 0,
            moderateConfidenceCount = 0,
            lowConfidenceCount = 0,
            qualityAssessment = "No active results"
        )

        /**
         * Calculates confidence score distribution across 5 quintile buckets (0-20%, 20-40%, 40-60%, 60-80%, 80-100%).
         */
        fun fromResults(results: List<SearchResult>): ConfidenceDistribution {
            if (results.isEmpty()) return EMPTY

            val total = results.size
            var sum = 0f
            var maxS = 0f
            var minS = 1f
            var highCount = 0
            var modCount = 0
            var lowCount = 0

            val binCounts = IntArray(5)

            for (res in results) {
                // Normalize score into [0.0, 1.0] range
                val score = res.cosineSimilarity.coerceIn(0f, 1f)
                sum += score
                if (score > maxS) maxS = score
                if (score < minS) minS = score

                when {
                    score >= 0.70f -> highCount++
                    score >= 0.40f -> modCount++
                    else -> lowCount++
                }

                // Bucket into 5 bins
                val binIndex = when {
                    score >= 0.80f -> 4
                    score >= 0.60f -> 3
                    score >= 0.40f -> 2
                    score >= 0.20f -> 1
                    else -> 0
                }
                binCounts[binIndex]++
            }

            val avg = sum / total
            val bins = listOf(
                ScoreBin("0-20%", 0.0f, 0.2f, binCounts[0], (binCounts[0].toFloat() / total) * 100f),
                ScoreBin("20-40%", 0.2f, 0.4f, binCounts[1], (binCounts[1].toFloat() / total) * 100f),
                ScoreBin("40-60%", 0.4f, 0.6f, binCounts[2], (binCounts[2].toFloat() / total) * 100f),
                ScoreBin("60-80%", 0.6f, 0.8f, binCounts[3], (binCounts[3].toFloat() / total) * 100f),
                ScoreBin("80-100%", 0.8f, 1.0f, binCounts[4], (binCounts[4].toFloat() / total) * 100f)
            )

            val assessment = when {
                avg >= 0.75f -> "Excellent Semantic Precision"
                avg >= 0.60f -> "Strong Match Relevance"
                avg >= 0.45f -> "Moderate Semantic Spread"
                else -> "Exploratory / Broad Matches"
            }

            return ConfidenceDistribution(
                bins = bins,
                averageScore = avg,
                maxScore = maxS,
                minScore = if (total > 0) minS else 0f,
                totalCount = total,
                highConfidenceCount = highCount,
                moderateConfidenceCount = modCount,
                lowConfidenceCount = lowCount,
                qualityAssessment = assessment
            )
        }
    }
}

/**
 * Preset timeframes for document creation / last-modified date filtering.
 */
enum class DateRangePreset(val displayName: String, val shortName: String) {
    ALL_TIME("All Time", "All Dates"),
    TODAY("Today", "Today"),
    PAST_7_DAYS("Past 7 Days", "7 Days"),
    PAST_30_DAYS("Past 30 Days", "30 Days"),
    PAST_90_DAYS("Past 90 Days", "90 Days"),
    CUSTOM("Custom Range", "Custom…")
}

/**
 * Calculates start and end timestamps in milliseconds for given date preset relative to reference time.
 */
fun calculateDateRangeBounds(preset: DateRangePreset, now: Long = System.currentTimeMillis()): Pair<Long?, Long?> {
    return when (preset) {
        DateRangePreset.ALL_TIME -> Pair(null, null)
        DateRangePreset.TODAY -> {
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val startOfDay = calendar.timeInMillis
            val endOfDay = startOfDay + 86_400_000L - 1L
            Pair(startOfDay, endOfDay)
        }
        DateRangePreset.PAST_7_DAYS -> {
            val start = now - (7L * 24 * 60 * 60 * 1000L)
            Pair(start, now + 3_600_000L)
        }
        DateRangePreset.PAST_30_DAYS -> {
            val start = now - (30L * 24 * 60 * 60 * 1000L)
            Pair(start, now + 3_600_000L)
        }
        DateRangePreset.PAST_90_DAYS -> {
            val start = now - (90L * 24 * 60 * 60 * 1000L)
            Pair(start, now + 3_600_000L)
        }
        DateRangePreset.CUSTOM -> Pair(null, null)
    }
}

/**
 * Formats a user-facing date range label for chips and filter indicators.
 */
fun formatDateRangeLabel(
    startDateMillis: Long?,
    endDateMillis: Long?,
    preset: DateRangePreset = DateRangePreset.CUSTOM
): String {
    if (startDateMillis == null && endDateMillis == null) return "All Dates"
    if (preset != DateRangePreset.CUSTOM && preset != DateRangePreset.ALL_TIME) {
        return preset.displayName
    }
    val sdf = java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return when {
        startDateMillis != null && endDateMillis != null -> {
            val startStr = sdf.format(java.util.Date(startDateMillis))
            val endStr = sdf.format(java.util.Date(endDateMillis))
            if (startStr == endStr) startStr else "$startStr – $endStr"
        }
        startDateMillis != null -> "From ${sdf.format(java.util.Date(startDateMillis))}"
        endDateMillis != null -> "Until ${sdf.format(java.util.Date(endDateMillis))}"
        else -> "All Dates"
    }
}

/**
 * Individual data point for top-k vector search confidence visualization in Recharts.
 */
data class TopKConfidenceScore(
    val rank: Int,
    val chunkId: Long,
    val fileName: String,
    val chunkIndex: Int,
    val scorePercentage: Float, // 0.0f .. 100.0f
    val rawCosineScore: Float,  // 0.0f .. 1.0f
    val rrfScore: Float,
    val tier: String,           // "HIGH", "MODERATE", "LOW"
    val timestamp: Long,
    val snippet: String
) {
    val tierColorHex: String
        get() = when (tier) {
            "HIGH" -> "#10B981"
            "MODERATE" -> "#F59E0B"
            else -> "#EF4444"
        }

    val formattedDate: String
        get() = if (timestamp > 0) {
            java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(java.util.Date(timestamp))
        } else "Unknown"
}

/**
 * Extracts top-k confidence scores from vector/hybrid search results for charting.
 */
fun extractTopKConfidenceScores(results: List<SearchResult>, k: Int = 10): List<TopKConfidenceScore> {
    if (results.isEmpty()) return emptyList()
    return results.take(k).mapIndexed { index, item ->
        val score = item.cosineSimilarity.coerceIn(0f, 1f)
        val tier = when {
            score >= 0.70f -> "HIGH"
            score >= 0.40f -> "MODERATE"
            else -> "LOW"
        }
        TopKConfidenceScore(
            rank = index + 1,
            chunkId = item.chunkId,
            fileName = item.fileName,
            chunkIndex = item.chunkIndex,
            scorePercentage = score * 100f,
            rawCosineScore = score,
            rrfScore = item.rrfScore,
            tier = tier,
            timestamp = item.timestamp,
            snippet = item.snippet
        )
    }
}

/**
 * Filter configuration for search results display.
 */
data class SearchFilterState(
    val selectedFileType: String? = null, // e.g. "txt", "pdf", "md", etc. null = All
    val sortOrder: SearchSortOrder = SearchSortOrder.RELEVANCE,
    val minConfidenceThreshold: Float? = null, // e.g. 0.70f for high match only
    val startDateMillis: Long? = null,
    val endDateMillis: Long? = null,
    val datePreset: DateRangePreset = DateRangePreset.ALL_TIME
) {
    /**
     * Applies this filter and sort configuration onto a list of search results.
     */
    fun apply(results: List<SearchResult>): List<SearchResult> {
        if (results.isEmpty()) return emptyList()

        var filtered = results

        // 1. File type filter
        if (!selectedFileType.isNullOrBlank() && !selectedFileType.equals("All", ignoreCase = true)) {
            val targetExt = selectedFileType.lowercase(Locale.ROOT).removePrefix(".")
            filtered = if (targetExt == "image" || targetExt == "images") {
                filtered.filter {
                    it.fileExtension in setOf("jpg", "jpeg", "png", "webp", "heic", "bmp")
                }
            } else {
                filtered.filter { it.fileExtension == targetExt }
            }
        }

        // 2. Confidence threshold filter
        if (minConfidenceThreshold != null) {
            filtered = filtered.filter { it.cosineSimilarity >= minConfidenceThreshold }
        }

        // 3. Date range filter (document creation / last-modified timestamp)
        if (startDateMillis != null) {
            filtered = filtered.filter { it.timestamp >= startDateMillis }
        }
        if (endDateMillis != null) {
            filtered = filtered.filter { it.timestamp <= endDateMillis }
        }

        // 4. Sorting
        return when (sortOrder) {
            SearchSortOrder.RELEVANCE -> filtered.sortedWith(
                compareByDescending<SearchResult> { if (it.combinedScore > 0f) it.combinedScore else it.rrfScore }
                    .thenByDescending { it.cosineSimilarity }
            )
            SearchSortOrder.DATE_DESC -> filtered.sortedByDescending { it.timestamp }
            SearchSortOrder.DATE_ASC -> filtered.sortedBy { it.timestamp }
            SearchSortOrder.FILE_SIZE -> filtered.sortedByDescending {
                if (it.fileSize > 0L) it.fileSize else it.chunkText.length.toLong()
            }
            SearchSortOrder.NAME_ASC -> filtered.sortedBy { it.fileName.lowercase(Locale.ROOT) }
            SearchSortOrder.NAME_DESC -> filtered.sortedByDescending { it.fileName.lowercase(Locale.ROOT) }
        }
    }
}
