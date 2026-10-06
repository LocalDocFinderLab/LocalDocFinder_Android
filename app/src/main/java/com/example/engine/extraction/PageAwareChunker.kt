package com.example.engine.extraction

/**
 * Splits page-structured text into chunks while remembering which page(s) each chunk came from, so a
 * search hit can say "page 12". Pure Kotlin (no Android types) so it is unit-testable on the JVM.
 */
object PageAwareChunker {

    data class PageChunk(val text: String, val page: Int, val pageEnd: Int)

    /**
     * @param targetChars size budget per chunk; adjacent small pieces (short pages, a page's tail) are
     *   merged up to this size instead of becoming tiny, low-signal chunks.
     * @param splitPage splits one page's text into pieces no larger than [targetChars]
     *   (typically the parser's paragraph/sentence-aware chunker).
     */
    fun chunk(
        pages: List<ExtractedPage>,
        targetChars: Int,
        splitPage: (String) -> List<String>
    ): List<PageChunk> {
        val out = ArrayList<PageChunk>()
        val buffer = StringBuilder()
        var bufferStart = 0
        var bufferEnd = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(PageChunk(buffer.toString(), bufferStart, bufferEnd))
                buffer.setLength(0)
            }
        }

        for (page in pages) {
            for (piece in splitPage(page.text)) {
                val text = piece.trim()
                if (text.isEmpty()) continue
                if (buffer.isNotEmpty() && buffer.length + SEPARATOR.length + text.length > targetChars) {
                    flush()
                }
                if (buffer.isEmpty()) {
                    bufferStart = page.number
                } else {
                    buffer.append(SEPARATOR)
                }
                buffer.append(text)
                bufferEnd = page.number
            }
        }
        flush()
        return out
    }

    private const val SEPARATOR = "\n\n"
}
