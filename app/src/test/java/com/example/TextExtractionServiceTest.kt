package com.example

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.example.engine.DocumentParser
import com.example.engine.ParseResult
import com.example.engine.extraction.TextExtractionService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end checks of the PDF text extraction service: a PDF on disk goes in, page-aware text comes out
 * and survives the DocumentParser chunking that feeds the Room index. Assertions are on the extracted
 * words (not on which extractor ran) so they hold whether PDFBox or the built-in fallback handled the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextExtractionServiceTest {

    private lateinit var context: Context
    private lateinit var pdfFile: File

    private val twoPagePdf = """
        %PDF-1.4
        1 0 obj
        << /Type /Catalog /Pages 2 0 R >>
        endobj
        2 0 obj
        << /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>
        endobj
        3 0 obj
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 7 0 R >> >> >>
        endobj
        4 0 obj
        << /Length 85 >>
        stream
        BT
        /F1 14 Tf
        50 750 Td
        (Lipid nanoparticles deliver mRNA vaccines) Tj
        ET
        endstream
        endobj
        5 0 obj
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>
        endobj
        6 0 obj
        << /Length 80 >>
        stream
        BT
        /F1 12 Tf
        50 700 Td
        (SQLite full text search ranks with BM25) Tj
        ET
        endstream
        endobj
        7 0 obj
        << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
        endobj
        trailer
        << /Size 8 /Root 1 0 R >>
        %%EOF
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pdfFile = File(context.cacheDir, "extraction_test.pdf").apply {
            writeBytes(twoPagePdf.toByteArray(Charsets.ISO_8859_1))
        }
    }

    @After
    fun tearDown() {
        pdfFile.delete()
    }

    @Test
    fun `extractPdf returns the text of every page`() {
        val doc = TextExtractionService(context).extractPdf(Uri.fromFile(pdfFile))
        assertFalse("PDF with text must not extract as empty (error=${doc.error})", doc.isEmpty)
        val text = doc.fullText
        assertTrue(text, text.contains("Lipid nanoparticles"))
        assertTrue(text, text.contains("BM25"))
    }

    @Test
    fun `extractPdf never throws on a file that is not a PDF`() {
        val junk = File(context.cacheDir, "junk.pdf").apply { writeText("this is not a pdf at all") }
        try {
            val doc = TextExtractionService(context).extractPdf(Uri.fromFile(junk))
            assertTrue(doc.isEmpty)
        } finally {
            junk.delete()
        }
    }

    @Test
    fun `extractPdf never throws on a missing file`() {
        val doc = TextExtractionService(context).extractPdf(Uri.fromFile(File(context.cacheDir, "nope.pdf")))
        assertTrue(doc.isEmpty)
    }

    @Test
    fun `parser chunks an extracted PDF and records page numbers`() = runBlocking {
        val result = DocumentParser(context).parseDocumentSafely(DocumentFile.fromFile(pdfFile))
        assertTrue("Expected a successful parse, got $result", result is ParseResult.Success)
        val parsed = (result as ParseResult.Success).document
        assertTrue(parsed.chunks.isNotEmpty())
        assertTrue(parsed.fullText.contains("Lipid nanoparticles"))
        // Text-bearing PDF chunks carry the page they came from.
        assertTrue(parsed.chunks.all { it.page != null && it.page!! >= 1 })
    }
}
