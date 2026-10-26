package com.example.engine.extraction

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.io.Writer

/**
 * PDF text extraction backed by PDFBox-Android (the Android port of Apache PDFBox).
 *
 * Employs a single-pass streaming text stripper to extract all document pages in O(N) time without
 * redundant catalog re-traversals. Handles font encodings, CID/Type0 maps, object streams, and layout order.
 */
internal object PdfBoxTextExtractor {

    private const val TAG = "PdfBoxTextExtractor"

    const val NAME = "pdfbox"

    /** PDFBox keeps this much of the file in RAM and spills the rest to disk scratch cache. */
    private const val MAIN_MEMORY_BYTES = 2L * 1024 * 1024
    const val DEFAULT_MAX_PAGES = 2_000
    const val DEFAULT_MAX_CHARS = 4_000_000
    /**
     * Stops reading pages after this long and keeps what was read. The caller's per-file budget also stops
     * extraction through `keepGoing`, so this is only a backstop.
     */
    private const val MAX_EXTRACTION_TIME_MS = 180_000L

    @Volatile
    private var initialised = false
    private var tempDir: java.io.File? = null

    /** PDFBox-Android loads glyph lists / font metrics from assets; this must run once per process. */
    fun ensureInitialised(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (!initialised) {
                PDFBoxResourceLoader.init(context.applicationContext)
                tempDir = java.io.File(context.cacheDir, "pdfbox_tmp").apply { mkdirs() }
                initialised = true
            }
        }
    }

    /**
     * Extracts every page's raw text in a single streaming pass.
     *
     * @param keepGoing polled between pages so a stopped indexing job can abandon a huge PDF.
     */
    fun extract(
        input: InputStream,
        maxPages: Int = DEFAULT_MAX_PAGES,
        maxChars: Int = DEFAULT_MAX_CHARS,
        keepGoing: () -> Boolean = { true }
    ): ExtractedDocument = PDDocument.load(input, memorySetting()).use { extract(it, maxPages, maxChars, keepGoing) }

    /**
     * Same as the stream version, but PDFBox reads the file in place instead of first copying all of it into
     * its scratch buffer, which matters for PDFs of tens of megabytes.
     */
    fun extract(
        file: File,
        maxPages: Int = DEFAULT_MAX_PAGES,
        maxChars: Int = DEFAULT_MAX_CHARS,
        keepGoing: () -> Boolean = { true }
    ): ExtractedDocument = PDDocument.load(file, memorySetting()).use { extract(it, maxPages, maxChars, keepGoing) }

    private fun memorySetting(): MemoryUsageSetting = try {
        val setting = MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES)
        tempDir?.let { setting.setTempDir(it) }
        setting
    } catch (_: Throwable) {
        MemoryUsageSetting.setupMainMemoryOnly(MAIN_MEMORY_BYTES)
    }

    private fun extract(
        doc: PDDocument,
        maxPages: Int,
        maxChars: Int,
        keepGoing: () -> Boolean
    ): ExtractedDocument {
        val info = doc.documentInformation
        val totalPages = doc.numberOfPages
        val metadata = ExtractedMetadata(
            title = info?.title?.trim()?.takeIf { it.isNotEmpty() },
            author = info?.author?.trim()?.takeIf { it.isNotEmpty() },
            subject = info?.subject?.trim()?.takeIf { it.isNotEmpty() },
            keywords = info?.keywords?.trim()?.takeIf { it.isNotEmpty() },
            pageCount = totalPages
        )

        val pageLimit = minOf(totalPages, maxPages)
        val stripper = StreamingPageTextStripper(
            maxChars = maxChars,
            maxTimeMs = MAX_EXTRACTION_TIME_MS,
            keepGoing = keepGoing
        )

        stripper.startPage = 1
        stripper.endPage = pageLimit

        try {
            stripper.writeText(doc, stripper.pageBuffer)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) {
                System.gc()
            }
            Log.w(TAG, "Streaming page extraction notice: ${t.message}")
        }

        val truncated = totalPages > maxPages || stripper.isTruncated
        return ExtractedDocument(stripper.extractedPages, metadata, NAME, truncated)
    }

    /**
     * PDFTextStripper that captures text page by page in a single pass through the document.
     *
     * Everything the stripper writes (words, the spaces between them, line and paragraph breaks) goes to
     * [pageBuffer], which is emptied into [extractedPages] at the end of each page. Overriding the individual
     * write methods instead loses the word separators, which PDFTextStripper writes straight to its output:
     * PDFs that position words rather than store space characters (LaTeX, most reports) came out as
     * "wordswithoutspaces".
     */
    private class StreamingPageTextStripper(
        private val maxChars: Int,
        private val maxTimeMs: Long,
        private val keepGoing: () -> Boolean
    ) : PDFTextStripper() {

        val extractedPages = ArrayList<ExtractedPage>()
        var totalChars = 0
        var isTruncated = false

        private val currentPageSb = StringBuilder()
        private val startTime = System.currentTimeMillis()
        private var pagesRead = 0

        val pageBuffer = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                currentPageSb.append(cbuf, off, len)
            }

            override fun write(str: String) {
                currentPageSb.append(str)
            }

            override fun write(str: String, off: Int, len: Int) {
                currentPageSb.append(str, off, off + len)
            }

            override fun flush() = Unit
            override fun close() = Unit
        }

        init {
            sortByPosition = true
            lineSeparator = "\n"
            // A blank line between paragraphs gives the chunker its split points.
            paragraphEnd = "\n"
            setAddMoreFormatting(true)
        }

        override fun startPage(page: PDPage?) {
            super.startPage(page)
            currentPageSb.setLength(0)
        }

        override fun endPage(page: PDPage?) {
            super.endPage(page)
            pagesRead++
            val text = currentPageSb.toString().trim()
            if (text.isNotBlank()) {
                // currentPageNo is the real page number: pages without a content stream never reach
                // startPage/endPage, so counting calls here would drift after the first empty page.
                extractedPages.add(ExtractedPage(currentPageNo, text))
                totalChars += text.length
            }
            currentPageSb.setLength(0)

            val elapsed = System.currentTimeMillis() - startTime
            if (totalChars >= maxChars || elapsed > maxTimeMs || !keepGoing()) {
                isTruncated = true
                endPage = currentPageNo
            }

            // Low-memory safeguard check every 10 pages
            if (pagesRead % 10 == 0) {
                val runtime = Runtime.getRuntime()
                val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                if (availableMemory < 30L * 1024 * 1024) {
                    System.gc()
                    val afterGc = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                    if (afterGc < 20L * 1024 * 1024) {
                        Log.w(TAG, "Low memory safeguard triggered at page $currentPageNo; pausing extraction safely.")
                        isTruncated = true
                        endPage = currentPageNo
                    }
                }
            }
        }
    }
}
