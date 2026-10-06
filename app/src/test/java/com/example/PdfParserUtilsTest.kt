package com.example

import com.example.engine.pdf.PdfParserUtils
import com.example.engine.pdf.PdfTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfParserUtilsTest {

    private val samplePdfData = """
        %PDF-1.4
        1 0 obj
        << /Type /Catalog /Pages 2 0 R >>
        endobj
        2 0 obj
        << /Type /Pages /Kids [3 0 R] /Count 1 >>
        endobj
        3 0 obj
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>
        endobj
        4 0 obj
        << /Length 110 >>
        stream
        BT
        /F1 12 Tf
        50 750 Td
        (BERT embedding models generate dense representations) Tj
        T*
        (for vector similarity search in Room database.) Tj
        ET
        endstream
        endobj
        5 0 obj
        << /Title (BERT Vector Embeddings) /Author (NLP Engineer) /Subject (Offline Search) /Keywords (BERT, Vector, PDF) >>
        endobj
        trailer
        << /Size 6 /Root 1 0 R /Info 5 0 R >>
        %%EOF
    """.trimIndent()

    @Test
    fun testExtractRawTextFromByteArrayAndStream() {
        val bytes = samplePdfData.toByteArray(Charsets.ISO_8859_1)

        assertTrue(PdfParserUtils.isPdf(bytes))

        val textFromBytes = PdfParserUtils.extractRawText(bytes)
        assertTrue(textFromBytes.contains("BERT embedding models"))
        assertTrue(textFromBytes.contains("vector similarity search in Room database"))

        val stream = ByteArrayInputStream(bytes)
        val textFromStream = PdfParserUtils.extractRawText(stream)
        assertEquals(textFromBytes, textFromStream)
    }

    @Test
    fun testExtractDocumentAndMetadata() {
        val bytes = samplePdfData.toByteArray(Charsets.ISO_8859_1)
        val stream = ByteArrayInputStream(bytes)
        val doc = PdfParserUtils.extractDocument(stream)

        assertTrue(doc.isSuccess)
        assertEquals("BERT Vector Embeddings", doc.metadata.title)
        assertEquals("NLP Engineer", doc.metadata.author)
        assertEquals("Offline Search", doc.metadata.subject)
        assertEquals("BERT, Vector, PDF", doc.metadata.keywords)
        assertEquals(1, doc.metadata.pageCount)
    }

    @Test
    fun testCleanPdfTextForEmbedding() {
        val messyPdfText = "BERT embedd-\ning models \t provide high-\naccuracy vectors.\r\n\r\n\r\nNext section here."
        val cleaned = PdfParserUtils.cleanPdfTextForEmbedding(messyPdfText)

        val expected = "BERT embedding models provide highaccuracy vectors.\n\nNext section here."
        assertEquals(expected, cleaned)
    }

    @Test
    fun testPrepareChunksForEmbedding() {
        val longText = (1..10).joinToString("\n\n") { "Paragraph $it: " + "Transformer attention mechanism converts tokens to dense representations. ".repeat(5) }
        val chunks = PdfParserUtils.prepareChunksForEmbedding(longText, targetChunkSize = 300, overlap = 50)

        assertTrue("Should split long PDF text into multiple chunks", chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.isNotBlank())
            assertTrue(chunk.length <= 400)
        }
    }
}
