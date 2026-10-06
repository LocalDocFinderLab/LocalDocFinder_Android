package com.example.engine.extraction

/**
 * Per-chunk facts kept in the `metadata` column of the `documents` Room table, as `;`-separated
 * `key=value` pairs, e.g. `model=bge_small_en_v15;page=3;pageEnd=4`. No schema change is needed.
 *
 *  - `model`: id of the embedding model that produced the chunk's vector. Vectors from different models
 *    live in unrelated spaces and must never be compared, so search only scores chunks whose model matches.
 *  - `page` / `pageEnd`: source PDF page range.
 */
object ChunkMetadata {

    private const val MODEL = "model"
    private const val PAGE = "page"
    private const val PAGE_END = "pageEnd"

    /** `model` is written first so SQL can match it with a simple prefix test (see the DAO). */
    fun encode(page: Int? = null, pageEnd: Int? = null, modelId: String? = null): String {
        val parts = ArrayList<String>(3)
        if (!modelId.isNullOrBlank()) parts.add("$MODEL=$modelId")
        if (page != null && page > 0) {
            parts.add("$PAGE=$page")
            pageEnd?.takeIf { it > page }?.let { parts.add("$PAGE_END=$it") }
        }
        return parts.joinToString(";")
    }

    fun forPages(page: Int?, pageEnd: Int?): String = encode(page, pageEnd)

    /** Returns [metadata] with its model id set to [modelId], keeping any page info. */
    fun withModel(metadata: String, modelId: String): String =
        encode(page(metadata), pageEnd(metadata), modelId)

    fun model(metadata: String): String? = rawValue(metadata, MODEL)?.takeIf { it.isNotEmpty() }

    fun page(metadata: String): Int? = rawValue(metadata, PAGE)?.toIntOrNull()

    fun pageEnd(metadata: String): Int? = rawValue(metadata, PAGE_END)?.toIntOrNull()

    /** "p. 3" or "pp. 3–4"; null when the chunk has no page info. */
    fun pageLabel(metadata: String): String? {
        val start = page(metadata) ?: return null
        val end = pageEnd(metadata)
        return if (end != null && end > start) "pp. $start–$end" else "p. $start"
    }

    private fun rawValue(metadata: String, key: String): String? =
        metadata.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
}
