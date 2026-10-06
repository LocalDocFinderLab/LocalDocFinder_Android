package com.example

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import com.example.engine.pdf.PdfTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTextExtractorTest {

    @Test
    fun testPdfTextExtractionMultiPageDocument() {
        val multiPagePdf = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>
            endobj
            4 0 obj
            << /Length 120 >>
            stream
            BT
            /F1 14 Tf
            50 750 Td
            (SQLite FTS5 Full Text Search with On Device Embeddings) Tj
            T*
            (Qualcomm Hexagon NPU hardware acceleration delegate) Tj
            ET
            endstream
            endobj
            5 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 6 0 R >>
            endobj
            6 0 obj
            << /Length 90 >>
            stream
            BT
            /F1 12 Tf
            50 700 Td
            (Page two containing vector index benchmarks and tokenization.) Tj
            ET
            endstream
            endobj
            xref
            0 7
            0000000000 65535 f 
            0000000009 00000 n 
            0000000058 00000 n 
            0000000122 00000 n 
            0000000213 00000 n 
            0000000385 00000 n 
            0000000476 00000 n 
            trailer
            << /Size 7 /Root 1 0 R >>
            startxref
            618
            %%EOF
        """.trimIndent()

        val pdfBytes = multiPagePdf.toByteArray(Charsets.ISO_8859_1)
        assertTrue("PDF byte stream should not be empty", pdfBytes.isNotEmpty())

        val result = PdfTextExtractor.extractFromBytes(pdfBytes)
        assertNotNull(result)
        assertTrue("Extraction should be successful", result.isSuccess)
        assertEquals(2, result.pageTexts.size)

        val extracted = result.fullText
        assertTrue(extracted.contains("SQLite FTS5 Full Text Search"))
        assertTrue(extracted.contains("Qualcomm Hexagon NPU"))
        assertTrue(extracted.contains("Page two containing vector index benchmarks"))
    }

    @Test
    fun testPdfTextExtractionSyntheticStream() {
        // Construct a standard minimal PDF representation with Flate stream and Tj / TJ operators
        val samplePdf = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R] /Count 1 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>
            endobj
            4 0 obj
            << /Length 95 >>
            stream
            BT
            /F1 12 Tf
            100 700 Td
            (LocalDoc Vector Search Engine Offline) Tj
            T*
            [(Hybrid) -150 (Reciprocal) -150 (Rank) -150 (Fusion)] TJ
            ET
            endstream
            endobj
            xref
            0 5
            0000000000 65535 f 
            0000000009 00000 n 
            0000000058 00000 n 
            0000000115 00000 n 
            0000000204 00000 n 
            trailer
            << /Size 5 /Root 1 0 R >>
            startxref
            350
            %%EOF
        """.trimIndent()

        val bytes = samplePdf.toByteArray(Charsets.ISO_8859_1)
        val result = PdfTextExtractor.extractFromBytes(bytes)

        assertTrue("Result should be successful", result.isSuccess)
        val text = result.fullText
        assertTrue(text.contains("LocalDoc Vector Search Engine Offline"))
        assertTrue(text.contains("Hybrid") && text.contains("Reciprocal") && text.contains("Rank") && text.contains("Fusion"))
    }

    @Test
    fun testPdfMetadataExtraction() {
        val samplePdfWithInfo = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R] /Count 1 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /Contents 4 0 R >>
            endobj
            4 0 obj
            << /Length 40 >>
            stream
            BT /F1 12 Tf (Content body) Tj ET
            endstream
            endobj
            5 0 obj
            << /Title (Deep Learning On Edge) /Author (AI Researcher) /Subject (Neural Vector Manifolds) /Keywords (LiteRT, Android, FTS) >>
            endobj
            trailer
            << /Root 1 0 R /Info 5 0 R >>
            %%EOF
        """.trimIndent()

        val bytes = samplePdfWithInfo.toByteArray(Charsets.ISO_8859_1)
        val result = PdfTextExtractor.extractFromBytes(bytes)

        assertEquals("Deep Learning On Edge", result.metadata.title)
        assertEquals("AI Researcher", result.metadata.author)
        assertEquals("Neural Vector Manifolds", result.metadata.subject)
        assertEquals("LiteRT, Android, FTS", result.metadata.keywords)
        assertTrue(result.fullText.contains("Deep Learning On Edge"))
        assertTrue(result.fullText.contains("Content body"))
    }
}
