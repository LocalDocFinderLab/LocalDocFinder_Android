package com.example.engine.extraction

/**
 * Per-chunk facts kept in the `metadata` column of the `documents` Room table, as `;`-separated
 * `key=value` pairs, e.g. `model=bge_small_en_v15;page=3;pageEnd=4`. No schema change is needed.
 *
 *  - `model`: id of the embedding model that produced the chunk's vector. Vectors from different models
 *    live in unrelated spaces and must never be compared, so search only scores chunks whose model matches.
 *  - `page` / `pageEnd`: source PDF page range.
 *  - `ocr=1`: the file's text was read with text recognition (pictures, scanned PDFs); files indexed before
 *    OCR existed lack it and are read again once.
 *  - `office=1`: a .doc/.xls/.ppt/.xlsx file read by the format-aware readers in `engine/office`; older
 *    entries (read as plain text, i.e. garbage) lack it and are read again once.
 */
object ChunkMetadata {

    private const val MODEL = "model"
    private const val PAGE = "page"
    private const val PAGE_END = "pageEnd"
    private const val OCR = "ocr"
    private const val OFFICE = "office"

    /** Extensions whose readers set the `office` flag. */
    val OFFICE_READER_EXTENSIONS = setOf("doc", "xls", "ppt", "xlsx")

    /** `model` is written first so SQL can match it with a simple prefix test (see the DAO). */
    fun encode(
        page: Int? = null,
        pageEnd: Int? = null,
        modelId: String? = null,
        ocr: Boolean = false,
        office: Boolean = false
    ): String {
        val parts = ArrayList<String>(5)
        if (!modelId.isNullOrBlank()) parts.add("$MODEL=$modelId")
        if (page != null && page > 0) {
            parts.add("$PAGE=$page")
            pageEnd?.takeIf { it > page }?.let { parts.add("$PAGE_END=$it") }
        }
        if (ocr) parts.add("$OCR=1")
        if (office) parts.add("$OFFICE=1")
        return parts.joinToString(";")
    }

    fun forPages(page: Int?, pageEnd: Int?): String = encode(page, pageEnd)

    /** Returns [metadata] with its model id set to [modelId], keeping any page, OCR and reader info. */
    fun withModel(metadata: String, modelId: String): String =
        encode(page(metadata), pageEnd(metadata), modelId, ocr(metadata), office(metadata))

    /** Returns [metadata] with the given flags added (flags already set stay set). */
    fun withFlags(metadata: String, ocr: Boolean, office: Boolean): String {
        val newOcr = ocr || ocr(metadata)
        val newOffice = office || office(metadata)
        if (newOcr == ocr(metadata) && newOffice == office(metadata)) return metadata
        return encode(page(metadata), pageEnd(metadata), model(metadata), newOcr, newOffice)
    }

    fun ocr(metadata: String): Boolean = rawValue(metadata, OCR) == "1"

    fun office(metadata: String): Boolean = rawValue(metadata, OFFICE) == "1"

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
