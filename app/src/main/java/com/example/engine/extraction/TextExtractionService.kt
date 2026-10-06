package com.example.engine.extraction

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.engine.pdf.PdfParserUtils
import com.example.engine.pdf.PdfTextExtractor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Turns a PDF into clean, page-aware text ready to be chunked, embedded and stored in Room.
 *
 * Extraction is tried from most to least capable and the first result with real text wins:
 *  1. **PDFBox-Android** — a full PDF parser (the same engine Apache Tika uses for PDFs; Tika itself
 *     can't run on Android because it depends on `java.awt` and `javax.xml.stream`).
 *  2. The built-in stream scanner / `PdfRenderer` fallback in [PdfTextExtractor], for files PDFBox rejects.
 *
 * The extracted pages feed the normal indexing pipeline (`DocumentParser` → chunks → embeddings →
 * `documents` table + FTS index), so extracted PDF text is searchable both by keyword and by vector.
 */
class TextExtractionService(private val context: Context) {

    companion object {
        private const val TAG = "TextExtractionService"
        const val FALLBACK_EXTRACTOR = "builtin"

        /** Pages shorter than this after cleaning are treated as noise (page numbers, scan artefacts). */
        private const val MIN_PAGE_CHARS = 2
    }

    /**
     * Extracts text from the PDF at [uri] (a `file://` or `content://` URI). Never throws: a file that
     * can't be read yields an empty [ExtractedDocument] with [ExtractedDocument.error] set.
     */
    fun extractPdf(uri: Uri, keepGoing: () -> Boolean = { true }): ExtractedDocument {
        var failure: String? = null

        try {
            PdfBoxTextExtractor.ensureInitialised(context)
            val result = openStream(uri).use { PdfBoxTextExtractor.extract(it, keepGoing = keepGoing) }
            val cleaned = cleanPages(result)
            if (!cleaned.isEmpty) {
                Log.i(TAG, "PDFBox extracted ${cleaned.pages.size}/${result.metadata.pageCount} page(s) from $uri")
                return cleaned
            }
            Log.i(TAG, "PDFBox found no text in $uri (likely a scanned PDF); trying built-in extractor")
        } catch (t: Throwable) {
            // PDFBox can throw IOException (corrupt / password protected) but also Error subclasses on
            // pathological files (StackOverflowError, OutOfMemoryError, LinkageError). None should kill indexing.
            if (t is OutOfMemoryError) {
                System.gc()
            }
            failure = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "PDFBox could not read $uri ($failure); trying built-in extractor")
        }

        return try {
            val fallback = PdfTextExtractor.extractDocument(context, uri)
            val pages = fallback.pageTexts.ifEmpty { if (fallback.fullText.isNotBlank()) listOf(fallback.fullText) else emptyList() }
                .mapIndexed { i, text -> ExtractedPage(i + 1, PdfParserUtils.cleanPdfTextForEmbedding(text)) }
                .filter { it.text.length >= MIN_PAGE_CHARS }
            val metadata = ExtractedMetadata(
                title = fallback.metadata.title,
                author = fallback.metadata.author,
                subject = fallback.metadata.subject,
                keywords = fallback.metadata.keywords,
                pageCount = fallback.metadata.pageCount
            )
            ExtractedDocument(pages, metadata, FALLBACK_EXTRACTOR, error = if (pages.isEmpty()) failure ?: fallback.error else null)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) {
                System.gc()
            }
            Log.w(TAG, "Built-in PDF extractor failed for $uri: ${t.message}")
            ExtractedDocument.empty(FALLBACK_EXTRACTOR, failure ?: t.message)
        }
    }

    private fun cleanPages(doc: ExtractedDocument): ExtractedDocument {
        val pages = doc.pages
            .map { ExtractedPage(it.number, PdfParserUtils.cleanPdfTextForEmbedding(it.text)) }
            .filter { it.text.length >= MIN_PAGE_CHARS }
        return doc.copy(pages = pages)
    }

    private fun openStream(uri: Uri): InputStream =
        if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
            FileInputStream(File(uri.path ?: uri.toString().removePrefix("file://")))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open stream for $uri")
        }
}
