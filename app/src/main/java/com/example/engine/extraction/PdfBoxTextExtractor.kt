package com.example.engine.extraction

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * PDF text extraction backed by PDFBox-Android (the Android port of Apache PDFBox).
 *
 * Compared with the built-in stream scanner it handles real-world PDFs properly: font encodings and
 * ToUnicode maps, Type0/CID fonts, object streams, encrypted-but-openable files, and reading order
 * (text is position-sorted and paragraph breaks are kept so the chunker can split on them).
 */
internal object PdfBoxTextExtractor {

    private const val TAG = "PdfBoxTextExtractor"

    const val NAME = "pdfbox"

    /** PDFBox keeps this much of the file in RAM and spills the rest to disk scratch cache. */
    private const val MAIN_MEMORY_BYTES = 2L * 1024 * 1024
    const val DEFAULT_MAX_PAGES = 150
    const val DEFAULT_MAX_CHARS = 1_000_000
    private const val MAX_EXTRACTION_TIME_MS = 25_000L

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
     * Extracts every page's raw text. Throws if the document cannot be opened at all (corrupt,
     * password-protected); a page that fails to extract is skipped instead of failing the file.
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

            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setLineSeparator("\n")
            // Emits blank lines between paragraphs, which the chunker uses as split points.
            stripper.setAddMoreFormatting(true)

            val pages = ArrayList<ExtractedPage>()
            var chars = 0
            var truncated = totalPages > maxPages
            val lastPage = minOf(totalPages, maxPages)
            val startTime = System.currentTimeMillis()

            for (pageNumber in 1..lastPage) {
                if (!keepGoing() || chars >= maxChars || (System.currentTimeMillis() - startTime) > MAX_EXTRACTION_TIME_MS) {
                    truncated = true
                    break
                }

                // Check memory health periodically to protect system and prevent low-memory killer terminations
                if (pageNumber % 5 == 0) {
                    val runtime = Runtime.getRuntime()
                    val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                    if (availableMemory < 30L * 1024 * 1024) {
                        System.gc()
                        val afterGc = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                        if (afterGc < 20L * 1024 * 1024) {
                            Log.w(TAG, "Low memory safeguard triggered at page $pageNumber; stopping extraction safely.")
                            truncated = true
                            break
                        }
                    }
                    // Cooperative yield to keep Android UI thread responsive
                    Thread.yield()
                }

                val text = try {
                    stripper.setStartPage(pageNumber)
                    stripper.setEndPage(pageNumber)
                    stripper.getText(doc)
                } catch (t: Throwable) {
                    if (t is OutOfMemoryError) {
                        System.gc()
                    }
                    Log.w(TAG, "Skipping unreadable page $pageNumber: ${t.message}")
                    ""
                }
                if (text.isNotBlank()) {
                    pages.add(ExtractedPage(pageNumber, text))
                    chars += text.length
                }
            }
            return ExtractedDocument(pages, metadata, NAME, truncated)
        }
    }
}
