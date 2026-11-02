package com.example.engine.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a chat model on the phone's CPU with the MediaPipe LLM Inference runtime. Nothing is sent anywhere: the
 * model file is read from app storage and generation happens in-process.
 *
 * One model is kept loaded (loading takes a few seconds); switching models closes the previous one. Each answer
 * uses a fresh session, so earlier questions never leak into the context except through the prompt.
 */
class LocalLlmEngine(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var inference: LlmInference? = null
    private var loadedFile: File? = null
    @Volatile private var activeSession: LlmInferenceSession? = null

    /** Context window of the loaded model (prompt + answer, tokens). */
    var contextTokens: Int = ChatModel.DEFAULT_CONTEXT_TOKENS
        private set

    val loadedModelFile: File? get() = loadedFile

    /** Loads [file] unless it is already loaded. Throws with a readable message if the runtime can't load it. */
    suspend fun load(file: File) {
        withContext(Dispatchers.IO) { lock.withLock { loadLocked(file) } }
    }

    private fun loadLocked(file: File) {
        run {
            if (loadedFile == file && inference != null) return
            closeLocked()
            val tokens = ChatModel.contextTokensOf(file.name)
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(tokens)
                .setMaxTopK(64)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            val started = System.currentTimeMillis()
            inference = try {
                LlmInference.createFromOptions(appContext, options)
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("On-device chat is not supported on this processor.", e)
            } catch (e: Throwable) {
                throw IllegalStateException("Could not load the model: ${e.message}", e)
            }
            loadedFile = file
            contextTokens = tokens
            Log.i(TAG, "Loaded ${file.name} in ${System.currentTimeMillis() - started} ms (context $tokens tokens)")
        }
    }

    /**
     * Streams the answer to [prompt] (already in the model's chat template) as the text generated so far.
     * Cancelling the collector stops generation.
     */
    fun generate(prompt: String, temperature: Float = 0.3f): Flow<String> = callbackFlow {
        val engine = inference ?: throw IllegalStateException("No model loaded")
        // A stopped answer may still be winding down in the runtime: close it before starting the next one.
        closeActiveSession()
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.9f)
            .setTemperature(temperature)
            .build()
        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
        activeSession = session
        val text = StringBuilder()
        val finished = AtomicBoolean(false)
        val abandoned = AtomicBoolean(false)
        try {
            session.addQueryChunk(prompt)
            session.generateResponseAsync(ProgressListener<String> { partial, done ->
                if (!abandoned.get()) {
                    if (partial != null) text.append(partial)
                    trySend(text.toString())
                }
                if (done) {
                    finished.set(true)
                    if (abandoned.get()) closeSession(session) else close()
                }
            })
        } catch (e: Throwable) {
            finished.set(true)
            close(IllegalStateException("The model could not answer: ${e.message}", e))
        }
        awaitClose {
            if (finished.get()) {
                closeSession(session)
            } else {
                // Stopped by the user: ask the runtime to stop; the session is closed once it reports done
                // (or before the next answer starts), never while native code is still writing into it.
                abandoned.set(true)
                try {
                    session.cancelGenerateResponseAsync()
                } catch (_: Throwable) {
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun closeSession(session: LlmInferenceSession) {
        synchronized(this) {
            try {
                session.close()
            } catch (_: Throwable) {
            }
            if (activeSession === session) activeSession = null
        }
    }

    private fun closeActiveSession() {
        activeSession?.let { closeSession(it) }
    }

    /** Frees the model's memory (hundreds of MB). */
    suspend fun close() {
        lock.withLock { closeLocked() }
    }

    private fun closeLocked() {
        closeActiveSession()
        try {
            inference?.close()
        } catch (_: Throwable) {
        }
        inference = null
        loadedFile = null
    }

    private companion object {
        const val TAG = "LocalLlmEngine"
    }
}
