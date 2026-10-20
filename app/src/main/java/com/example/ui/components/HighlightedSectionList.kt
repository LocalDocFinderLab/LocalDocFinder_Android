package com.example.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.example.engine.KeywordHighlighter

/**
 * A document's text as a lazy list of short blocks, highlighted only when scrolled into view.
 *
 * One `Text` holding a whole document (up to millions of characters) has to be laid out in full before it
 * can draw, which froze the preview and made it impossible to scroll.
 */
@Composable
fun HighlightedSectionList(
    sections: List<String>,
    keywords: List<String>,
    query: String,
    filterKeyword: String?,
    highlightColor: Color,
    highlightBgColor: Color,
    baseTextColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    spacing: Dp = 10.dp
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        itemsIndexed(items = sections, key = { index, _ -> index }) { _, section ->
            val annotated = remember(section, keywords, query, filterKeyword, highlightColor, highlightBgColor, baseTextColor) {
                KeywordHighlighter.highlightText(
                    text = section,
                    keywords = keywords,
                    query = query,
                    filterKeyword = filterKeyword,
                    highlightColor = highlightColor,
                    highlightBgColor = highlightBgColor,
                    baseTextColor = baseTextColor
                ).annotatedString
            }
            Text(
                text = annotated,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily
            )
        }
    }
}
