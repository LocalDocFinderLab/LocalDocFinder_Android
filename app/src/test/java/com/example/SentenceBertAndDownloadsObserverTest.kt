package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.SentenceBertTfliteEngine
import com.example.engine.model.EmbeddingModelType
import com.example.engine.model.UnifiedEmbeddingManager
import com.example.worker.DownloadsFileObserverWorker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SentenceBertAndDownloadsObserverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSentenceBertEngineTokenizationAndEmbeddings() = runTest {
        val engine = SentenceBertTfliteEngine(context)
        val text = "Artificial intelligence document vector search with Sentence-BERT"

        val encoding = engine.tokenize(text)
        assertNotNull(encoding)
        assertTrue(encoding.inputIds.isNotEmpty())
        assertEquals(SentenceBertTfliteEngine.CLS_TOKEN_ID, encoding.inputIds[0])

        val vector = engine.embedText(text)
        assertNotNull(vector)
        assertEquals(384, vector.size)

        // Verify L2 normalization: sqrt(sum(v_i^2)) ~ 1.0
        var normSq = 0f
        for (v in vector) {
            normSq += v * v
        }
        val norm = kotlin.math.sqrt(normSq)
        assertTrue("Vector norm should be approximately 1.0, was $norm", kotlin.math.abs(norm - 1.0f) < 0.05f)
    }

    @Test
    fun testUnifiedEmbeddingManagerWithSentenceBert() = runTest {
        val manager = UnifiedEmbeddingManager(context)
        manager.setActiveModel(EmbeddingModelType.ONNX_SENTENCE_BERT_TFLITE)

        assertEquals(EmbeddingModelType.ONNX_SENTENCE_BERT_TFLITE, manager.getActiveModel())

        val vec = manager.embedText("Query for local ONNX Sentence-BERT model")
        assertEquals(384, vec.size)
    }

    @Test
    fun testDownloadsFileObserverWorkerScheduling() {
        DownloadsFileObserverWorker.scheduleDownloadsObserver(context)
        val workId = DownloadsFileObserverWorker.triggerImmediateScan(context)
        assertNotNull(workId)
    }
}
