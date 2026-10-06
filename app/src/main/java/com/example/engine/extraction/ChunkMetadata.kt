package com.example.engine.extraction

/**
 * Encodes per-chunk source info (currently the PDF page range) into the `metadata` column of the
 * `documents` Room table, e.g. `page=3` or `page=3;pageEnd=4`. No schema change is needed.
 */
object ChunkMetadata {

    private const val PAGE = "page"
    private const val PAGE_END = "pageEnd"

    fun forPages(page: Int?, pageEnd: Int?): String {
        if (page == null || page <= 0) return ""
        val end = pageEnd?.takeIf { it > page }
        return if (end == null) "$PAGE=$page" else "$PAGE=$page;$PAGE_END=$end"
    }

    fun page(metadata: String): Int? = value(metadata, PAGE)

    fun pageEnd(metadata: String): Int? = value(metadata, PAGE_END)

    /** "p. 3" or "pp. 3–4"; null when the chunk has no page info. */
    fun pageLabel(metadata: String): String? {
        val start = page(metadata) ?: return null
        val end = pageEnd(metadata)
        return if (end != null && end > start) "pp. $start–$end" else "p. $start"
    }

    private fun value(metadata: String, key: String): Int? =
        metadata.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
}
