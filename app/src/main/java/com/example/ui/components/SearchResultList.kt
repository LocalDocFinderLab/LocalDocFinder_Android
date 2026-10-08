package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.KeywordHighlighter
import com.example.engine.SearchMode
import com.example.engine.SearchResult
import java.util.Locale

/**
 * Search results list for SQLite FTS (keyword) results ranked by semantic relevance.
 *
 * Purely presentational: the results and loading flag come from [com.example.ui.MainViewModel], so
 * ranking, filters and sort order are applied in one place. Renders one of three states:
 * - shimmer skeleton while the engine is still processing the query,
 * - [SearchEmptyState] (illustration + tips) when nothing matches,
 * - the result rows.
 */
@Composable
fun SearchResultList(
    query: String,
    results: List<SearchResult>,
    isSearching: Boolean,
    onResultClick: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
    searchMode: SearchMode = SearchMode.KEYWORD,
    hasActiveFilters: Boolean = false,
    onSwitchToHybrid: () -> Unit = {},
    onClearFilters: () -> Unit = {},
    onAddDocuments: (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when {
            results.isEmpty() && isSearching -> SearchResultsShimmer()
            results.isEmpty() && query.isNotBlank() -> SearchEmptyState(
                query = query,
                searchMode = searchMode,
                hasActiveFilters = hasActiveFilters,
                onSwitchToHybrid = onSwitchToHybrid,
                onClearFilters = onClearFilters,
                onAddDocuments = onAddDocuments
            )
            results.isNotEmpty() -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("fts_realtime_results_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
            ) {
                items(
                    items = results,
                    key = { it.chunkId }
                ) { result ->
                    SearchResultRow(
                        result = result,
                        query = query,
                        onClick = { onResultClick(result) }
                    )
                }
            }
        }
    }
}

@Composable
fun SearchResultRow(
    result: SearchResult,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = result.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val badgeColor = getFileTypeBadgeColor(ext)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag("realtime_result_card_${result.chunkId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File Type Badge
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (ext.isNotEmpty() && ext.length <= 4) ext else "DOC",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Document Name/Title
                    Text(
                        text = result.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = buildString {
                            append("Chunk #${result.chunkIndex + 1}")
                            if (result.cosineSimilarity > 0f) {
                                append(" • Semantic ${(result.cosineSimilarity * 100).toInt()}%")
                            }
                            append(" • BM25 %.2f".format(result.bm25Score))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Match Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "FTS MATCH",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Highlighted Snippet text showing matching query terms in bold/color
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            ) {
                Text(
                    text = KeywordHighlighter.buildHighlightedSnippet(
                        snippet = result.snippet,
                        terms = result.highlightedTerms,
                        query = query,
                        highlightColor = MaterialTheme.colorScheme.primary,
                        highlightBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                        baseTextColor = MaterialTheme.colorScheme.onSurface,
                        boldMatchingTerms = true
                    ),
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
