package com.example

import com.example.engine.DocumentParser
import com.example.engine.OnDeviceEmbeddingEngine
import com.example.engine.Tokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorSearchEngineTest {

    @Test
    fun testWordPieceTokenizer() {
        val tokenizer = Tokenizer(maxSequenceLength = 64)
        val encoding = tokenizer.encode("LiteRT neural vector search on Android devices")

        assertNotNull(encoding)
        assertTrue(encoding.tokens.contains(Tokenizer.CLS_TOKEN))
        assertTrue(encoding.tokens.contains(Tokenizer.SEP_TOKEN))
        assertTrue(encoding.inputIds.isNotEmpty())
        assertEquals(64, encoding.inputIds.size)
        assertEquals(Tokenizer.CLS_ID, encoding.inputIds[0])
    }

    @Test
    fun testRecursiveTextChunker() {
        val parser = DocumentParser(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            targetChunkCharacters = 200,
            overlapCharacters = 40
        )
        val sampleText = "Paragraph 1 with some technical details.\n\nParagraph 2 describing vector embeddings and on-device machine learning inference.\n\nParagraph 3 discussing hybrid reciprocal rank fusion."
        val chunks = parser.recursiveTextChunk(sampleText, "test://doc1")

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue(chunk.text.isNotBlank())
            assertTrue(chunk.hash.isNotBlank())
            assertEquals(64, chunk.hash.length) // SHA-256 hex length
        }
    }

    @Test
    fun testCosineSimilarityNormalization() {
        runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val engine = OnDeviceEmbeddingEngine(context)

            val vecA = engine.embedText("artificial intelligence machine learning")
            val vecB = engine.embedText("deep neural network artificial intelligence")
            val vecC = engine.embedText("cooking pasta and tomato soup recipe")

            val simAB = engine.cosineSimilarity(vecA, vecB)
            val simAC = engine.cosineSimilarity(vecA, vecC)

            // Related concepts should have higher cosine similarity than unrelated ones
            assertTrue("Expected simAB ($simAB) > simAC ($simAC)", simAB > simAC)
        }
    }

    @Test
    fun testBatchProcessingLayer() {
        runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val engine = OnDeviceEmbeddingEngine(context)

            val batchTexts = listOf(
                "LiteRT NPU acceleration with Qualcomm QNN delegate",
                "SQLite FTS5 keyword indexing with BM25 score",
                "Distributed consensus Raft protocol for leader election",
                "Quantum entanglement and Shor integer factorization algorithm",
                "Storage access framework and persistent permissions",
                "Nucleoside-modified mRNA vaccines and lipid nanoparticles",
                "WordPiece tokenizer mapping subwords to vocabulary IDs",
                "Reciprocal Rank Fusion merging dense and sparse scores"
            )

            val embeddings = engine.embedBatch(batchTexts, batchSize = 4)
            assertEquals(8, embeddings.size)
            embeddings.forEach { vec ->
                assertEquals(384, vec.size)
                // Verify L2 norm is ~1.0
                var sumSq = 0.0
                for (v in vec) sumSq += (v * v).toDouble()
                val norm = Math.sqrt(sumSq)
                assertTrue("Expected normalized vector, got norm=$norm", Math.abs(norm - 1.0) < 1e-4)
            }
        }
    }

    @Test
    fun testChunkBatchProcessor() {
        runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val engine = OnDeviceEmbeddingEngine(context)
            val processor = com.example.engine.ChunkBatchProcessor(engine, targetBatchSize = 4)

            var dispatches = 0
            val texts = listOf("Chunk 1", "Chunk 2", "Chunk 3", "Chunk 4", "Chunk 5")
            processor.addAll(texts) { count ->
                dispatches++
            }
            processor.flush { count ->
                dispatches++
            }

            val allResults = processor.getAllResults()
            assertEquals(5, allResults.size)
            assertTrue("Expected at least 2 batch dispatches (4 + 1)", dispatches >= 2)
        }
    }

    @Test
    fun test100SampleDocumentsGenerator() {
        val defs = com.example.engine.SampleDocumentGenerator.getSampleDefinitions()
        assertEquals(102, defs.size)

        val imageCount = defs.count { it.isImage }
        assertEquals(12, imageCount)

        val textCount = defs.count { !it.isImage }
        assertEquals(90, textCount)

        val pdfCount = defs.count { it.isPdf }
        assertTrue("Expected minimal PDF files in sample corpus", pdfCount > 0)

        defs.forEach { item ->
            assertTrue(item.fileName.isNotBlank())
            assertTrue(item.content.isNotBlank())
        }
    }

    @Test
    fun testSystemFileExclusionAndFiltering() {
        runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val parser = DocumentParser(context)

            // Check ignored extensions include executables, packages, db, and videos
            val ignored = DocumentParser.IGNORED_EXTENSIONS
            assertTrue(ignored.contains("apk"))
            assertTrue(ignored.contains("dex"))
            assertTrue(ignored.contains("so"))
            assertTrue(ignored.contains("exe"))
            assertTrue(ignored.contains("db"))
            assertTrue(ignored.contains("sqlite"))
            assertTrue(ignored.contains("mp4"))
            assertTrue(ignored.contains("mkv"))

            // Check supported extensions only include regular docs and images
            val supported = DocumentParser.SUPPORTED_EXTENSIONS
            assertTrue(supported.contains("txt"))
            assertTrue(supported.contains("md"))
            assertTrue(supported.contains("pdf"))
            assertTrue(supported.contains("docx"))
            assertTrue(supported.contains("jpg"))
            assertTrue(supported.contains("png"))
            assertTrue(!supported.contains("mp4"))
            assertTrue(!supported.contains("apk"))
            assertTrue(!supported.contains("db"))

            // Create temporary test directory with mixed files
            val tempDir = java.io.File(context.cacheDir, "test_mixed_system_dir").apply { mkdirs() }
            val docFile = java.io.File(tempDir, "regular_notes.txt").apply { writeText("Sample text") }
            val pdfFile = java.io.File(tempDir, "contract.pdf").apply { writeText("%PDF-1.4 sample") }
            val apkFile = java.io.File(tempDir, "malicious.apk").apply { writeText("binary package data") }
            val dbFile = java.io.File(tempDir, "user_data.db").apply { writeText("sqlite binary header") }
            val videoFile = java.io.File(tempDir, "vacation_clip.mp4").apply { writeText("video container stream") }

            val scannedFiles = parser.scanDirectory(android.net.Uri.fromFile(tempDir))
            val scannedNames = scannedFiles.mapNotNull { it.name }

            assertTrue(scannedNames.contains("regular_notes.txt"))
            assertTrue(scannedNames.contains("contract.pdf"))
            assertTrue(!scannedNames.contains("malicious.apk"))
            assertTrue(!scannedNames.contains("user_data.db"))
            assertTrue(!scannedNames.contains("vacation_clip.mp4"))

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testChatParserSmsXml() {
        val parser = com.example.engine.ChatParser()
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <smses count="2">
              <sms protocol="0" address="+15551234567" date="1709280000000" type="1" subject="null" body="Did you test the vector search on device?" contact_name="Alice" />
              <sms protocol="0" address="+15551234567" date="1709280120000" type="2" subject="null" body="Yes Alice, LiteRT NPU embedding works completely offline!" contact_name="Alice" />
            </smses>
        """.trimIndent()

        val chunks = parser.parseSmsBackupXml(xml.byteInputStream(), "test_sms.xml")
        assertTrue("Expected parsed chat chunks from SMS XML", chunks.isNotEmpty())
        val chunk = chunks[0]
        assertTrue(chunk.contains("Alice"))
        assertTrue(chunk.contains("vector search on device"))
        assertTrue(chunk.contains("LiteRT NPU embedding"))
        assertTrue(chunk.contains("[Incoming]"))
        assertTrue(chunk.contains("[Outgoing]"))
    }

    @Test
    fun testChatParserWhatsApp() {
        val parser = com.example.engine.ChatParser()
        val whatsapp = """
            [12/10/24, 14:32:05] Bob: Hey, can we pause indexing when gaming?
            [12/10/24, 14:33:10] Me: Yes, Gaming Mode suspends tensor processing instantly.
        """.trimIndent()

        val chunks = parser.parseChatStream(whatsapp.byteInputStream(), "Bob Chat")
        assertTrue("Expected parsed WhatsApp chunks", chunks.isNotEmpty())
        assertTrue(chunks[0].contains("Gaming Mode suspends tensor processing"))
        assertTrue(chunks[0].contains("Bob"))
    }

    @Test
    fun testChatParserJson() {
        val parser = com.example.engine.ChatParser()
        val json = """
            [
              {"address": "+15559876543", "contact_name": "Charlie", "type": 1, "body": "Where is the project documentation stored?", "readable_date": "2024-03-01 12:00"},
              {"address": "+15559876543", "contact_name": "Charlie", "type": 2, "body": "In the local Room database with FTS4 index.", "readable_date": "2024-03-01 12:01"}
            ]
        """.trimIndent()

        val chunks = parser.parseSmsJson(json.byteInputStream(), "charlie_chat.json")
        assertTrue("Expected parsed JSON chat chunks", chunks.isNotEmpty())
        assertTrue(chunks[0].contains("Charlie"))
        assertTrue(chunks[0].contains("local Room database"))
    }

    @Test
    fun testSearchHistoryLocalOnly() {
        runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val repo = com.example.data.repository.DocumentRepository(context)

            repo.clearSearchHistory()
            repo.recordSearchQuery("offline vector embeddings", com.example.engine.SearchMode.HYBRID, 12)
            repo.recordSearchQuery("qualcomm qnn delegate", com.example.engine.SearchMode.VECTOR, 5)

            val db = com.example.data.local.AppDatabase.getInstance(context)
            val history = db.searchHistoryDao().getRecentSearchesDirect(10)

            assertEquals(2, history.size)
            assertEquals("qualcomm qnn delegate", history[0].query)
            assertEquals(5, history[0].resultCount)
            assertEquals("offline vector embeddings", history[1].query)

            // Test duplicate re-query updates order
            repo.recordSearchQuery("offline vector embeddings", com.example.engine.SearchMode.HYBRID, 14)
            val updated = db.searchHistoryDao().getRecentSearchesDirect(10)
            assertEquals(2, updated.size)
            assertEquals("offline vector embeddings", updated[0].query)
            assertEquals(14, updated[0].resultCount)

            // Clean up
            repo.clearSearchHistory()
            val cleared = db.searchHistoryDao().getRecentSearchesDirect(10)
            assertTrue(cleared.isEmpty())
        }
    }

    @Test
    fun testHardwareMonitorGamingPause() {
        val monitor = com.example.engine.HardwareMonitor
        monitor.setIndexingPaused(false)
        assertEquals(false, monitor.isIndexingPaused.value)

        val toggled = monitor.toggleIndexingPaused()
        assertEquals(true, toggled)
        assertEquals(true, monitor.isIndexingPaused.value)

        monitor.setIndexingPaused(false)
        assertEquals(false, monitor.isIndexingPaused.value)
    }

    @Test
    fun testChatExclusionToggle() {
        runBlocking {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val parser = com.example.engine.DocumentParser(context)

            val tempDir = java.io.File(context.cacheDir, "test_chat_exclusion_dir").apply { mkdirs() }
            val docFile = java.io.File(tempDir, "regular_notes.txt").apply { writeText("Sample text") }
            val chatFile = java.io.File(tempDir, "sms_backup_2024.xml").apply { writeText("<smses></smses>") }
            val whatsappFile = java.io.File(tempDir, "WhatsApp Chat with Alice.txt").apply { writeText("chat log") }

            // When user explicitly disables chat backups
            parser.includeChatBackups = false
            val scannedDefault = parser.scanDirectory(android.net.Uri.fromFile(tempDir))
            val namesDefault = scannedDefault.mapNotNull { it.name }
            assertTrue(namesDefault.contains("regular_notes.txt"))
            assertTrue("Chat XML should be excluded when false", !namesDefault.contains("sms_backup_2024.xml"))
            assertTrue("WhatsApp TXT should be excluded when false", !namesDefault.contains("WhatsApp Chat with Alice.txt"))

            // When user explicitly enables chat backups
            parser.includeChatBackups = true
            val scannedWithChats = parser.scanDirectory(android.net.Uri.fromFile(tempDir))
            val namesWithChats = scannedWithChats.mapNotNull { it.name }
            assertTrue(namesWithChats.contains("regular_notes.txt"))
            assertTrue(namesWithChats.contains("sms_backup_2024.xml"))
            assertTrue(namesWithChats.contains("WhatsApp Chat with Alice.txt"))

            tempDir.deleteRecursively()
        }
    }
}
