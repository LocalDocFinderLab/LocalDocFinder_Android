package com.example.engine.extraction

/** Text of one PDF page. [number] is 1-based and matches the page number a reader sees. */
data class ExtractedPage(val number: Int, val text: String)

data class ExtractedMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val pageCount: Int = 0
)

/**
 * Result of running a document through [TextExtractionService]: per-page text (blank pages are
 * dropped), document metadata, and which extractor produced the text.
 */
data class ExtractedDocument(
    val pages: List<ExtractedPage>,
    val metadata: ExtractedMetadata = ExtractedMetadata(),
    val extractor: String,
    /** True when extraction stopped early (page/character cap reached or the caller cancelled). */
    val truncated: Boolean = false,
    val error: String? = null
) {
    val isEmpty: Boolean get() = pages.isEmpty()

    val fullText: String get() = pages.joinToString("\n\n") { it.text }

    companion object {
        fun empty(extractor: String, error: String? = null) =
            ExtractedDocument(emptyList(), ExtractedMetadata(), extractor, error = error)
    }
}
