package com.example.engine.extraction

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import java.io.StringWriter

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
    const val DEFAULT_MAX_PAGES = 500
    const val DEFAULT_MAX_CHARS = 3_000_000
    private const val MAX_EXTRACTION_TIME_MS = 60_000L

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
    ): ExtractedDocument {
        val memorySetting = try {
            val setting = MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES)
            tempDir?.let { setting.setTempDir(it) }
            setting
        } catch (_: Throwable) {
            MemoryUsageSetting.setupMainMemoryOnly(MAIN_MEMORY_BYTES)
        }

        PDDocument.load(input, memorySetting).use { doc ->
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
                maxPages = pageLimit,
                maxChars = maxChars,
                maxTimeMs = MAX_EXTRACTION_TIME_MS,
                keepGoing = keepGoing
            )

            stripper.startPage = 1
            stripper.endPage = pageLimit

            try {
                stripper.writeText(doc, StringWriter())
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) {
                    System.gc()
                }
                Log.w(TAG, "Streaming page extraction notice: ${t.message}")
            }

            val truncated = totalPages > maxPages || stripper.isTruncated
            return ExtractedDocument(stripper.extractedPages, metadata, NAME, truncated)
        }
    }

    /**
     * Custom PDFTextStripper that captures text page-by-page in a single pass through the document.
     * Avoids O(N^2) page catalog lookups by overriding writeString and endPage.
     */
    private class StreamingPageTextStripper(
        private val maxPages: Int,
        private val maxChars: Int,
        private val maxTimeMs: Long,
        private val keepGoing: () -> Boolean
    ) : PDFTextStripper() {

        val extractedPages = ArrayList<ExtractedPage>()
        var totalChars = 0
        var isTruncated = false

        private val currentPageSb = StringBuilder()
        private val startTime = System.currentTimeMillis()
        private var pageNumberCounter = 0

        init {
            sortByPosition = true
            lineSeparator = "\n"
            setAddMoreFormatting(true)
        }

        override fun startPage(page: PDPage?) {
            super.startPage(page)
            pageNumberCounter++
            currentPageSb.setLength(0)
        }

        override fun endPage(page: PDPage?) {
            super.endPage(page)
            val text = currentPageSb.toString().trim()
            if (text.isNotBlank()) {
                extractedPages.add(ExtractedPage(pageNumberCounter, text))
                totalChars += text.length
            }
            currentPageSb.setLength(0)

            val elapsed = System.currentTimeMillis() - startTime
            if (pageNumberCounter >= maxPages || totalChars >= maxChars || elapsed > maxTimeMs || !keepGoing()) {
                isTruncated = true
                endPage = pageNumberCounter
            }

            // Low-memory safeguard check every 10 pages
            if (pageNumberCounter % 10 == 0) {
                val runtime = Runtime.getRuntime()
                val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                if (availableMemory < 30L * 1024 * 1024) {
                    System.gc()
                    val afterGc = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                    if (afterGc < 20L * 1024 * 1024) {
                        Log.w(TAG, "Low memory safeguard triggered at page $pageNumberCounter; pausing extraction safely.")
                        isTruncated = true
                        endPage = pageNumberCounter
                    }
                }
            }
        }

        override fun writeString(text: String?) {
            if (text != null) {
                currentPageSb.append(text)
            }
        }

        override fun writeLineSeparator() {
            currentPageSb.append("\n")
        }

        override fun writeParagraphEnd() {
            currentPageSb.append("\n\n")
        }
    }
}
