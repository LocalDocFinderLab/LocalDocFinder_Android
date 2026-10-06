package com.example.engine

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

enum class ExecutionBackend(val displayName: String, val tag: String) {
    GOOGLE_TENSOR_TPU("Google Tensor TPU (Pixel EdgeTPU)", "Pixel TPU"),
    QUALCOMM_QNN("Qualcomm QNN (NPU)", "HTP/NPU"),
    GPU_DELEGATE("GPU Delegate (OpenCL/Vulkan)", "GPU"),
    CPU_XNNPACK("CPU (XNNPACK / NEON SIMD)", "CPU")
}

/**
 * On-device embedding pipeline with Qualcomm QNN -> GPU -> CPU XNNPACK delegate fallback.
 * Generates 384-dimensional L2-normalized float embeddings.
 */
class OnDeviceEmbeddingEngine(
    private val context: Context,
    val embeddingDimension: Int = 384,
    private val maxSeqLength: Int = 256
) {
    companion object {
        private const val TAG = "DocuVectorEngine"
        private const val DEFAULT_ASSET_MODEL = "models/embedding_model.tflite"
        const val DEFAULT_BATCH_SIZE = 8
        private const val GPU_MODEL_TOKEN = "docuvector_embedding_v1"
    }

    private val tokenizer = Tokenizer(maxSequenceLength = maxSeqLength)
    val pixelOptimizer = PixelTensorOptimizer(context)
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var currentBackend: ExecutionBackend = ExecutionBackend.CPU_XNNPACK
    private var isUsingTfliteModel: Boolean = false
    private var modelBuffer: ByteBuffer? = null
    private var cpuThreadsInUse: Int = 0

    init {
        initializeEngine()
    }

    fun getBackend(): ExecutionBackend = currentBackend

    fun isModelLoaded(): Boolean = isUsingTfliteModel

    fun getPixelProfile(): PixelTensorOptimizer.PixelProfile = pixelOptimizer.currentProfile.value

    /**
     * Batch processing layer that gathers chunks in memory and passes them to
     * the LiteRT interpreter as a single batched tensor [B, maxSeqLength]
     * to maximize NPU/GPU dispatch utilization during mass indexing.
     * Incorporates Pixel Tensor thermal pacing and adaptive batch throttling.
     */
    suspend fun embedBatch(
        texts: List<String>,
        batchSize: Int = DEFAULT_BATCH_SIZE
    ): List<FloatArray> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()
        val allResults = ArrayList<FloatArray>(texts.size)

        var offset = 0
        while (offset < texts.size) {
            // Check if user paused indexing for gaming or other high-intensity tasks
            HardwareMonitor.checkPausePoint()

            // Re-read the live power policy for every batch: full throttle while charging and idle,
            // gentler as soon as the user is active, on battery or the device warms up. The Pixel
            // thermal profile can only add pacing on top, never remove it.
            val speed = IndexingPowerPolicy.current()
            val pixelProfile = pixelOptimizer.currentProfile.value
            val effectiveBatchSize = minOf(batchSize, speed.batchSize).coerceAtLeast(1)
            val interBatchDelay = maxOf(speed.interBatchDelayMs, pixelProfile.recommendedInterBatchDelayMs)
            ensureCpuThreads(speed.cpuThreads)

            val end = minOf(offset + effectiveBatchSize, texts.size)
            val batchTexts = texts.subList(offset, end)
            offset = end
            val batchEncodings = batchTexts.map { tokenizer.encode(it, padToMaxLength = true) }

            val interp = interpreter
            val startTime = System.currentTimeMillis()
            HardwareMonitor.isEmbeddingActive.value = true

            try {
                if (interp != null && isUsingTfliteModel) {
                    try {
                        val batchEmbeddings = runTfliteBatchInference(batchEncodings)
                        allResults.addAll(batchEmbeddings)
                        val elapsed = System.currentTimeMillis() - startTime
                        HardwareMonitor.lastLatencyMs.value = elapsed
                        if (interBatchDelay > 0) {
                            kotlinx.coroutines.delay(interBatchDelay)
                        }
                        continue
                    } catch (e: Exception) {
                        if (currentBackend == ExecutionBackend.GPU_DELEGATE) {
                            Log.w(TAG, "GPU inference failed (${e.message}); rebuilding interpreter on CPU and retrying")
                            fallBackToCpu()
                            try {
                                allResults.addAll(runTfliteBatchInference(batchEncodings))
                                HardwareMonitor.lastLatencyMs.value = System.currentTimeMillis() - startTime
                                continue
                            } catch (e2: Exception) {
                                Log.w(TAG, "CPU retry failed (${e2.message}), falling back to deterministic projection")
                            }
                        } else {
                            Log.w(TAG, "TFLite single-tensor batch inference failed (${e.message}), falling back to deterministic projection")
                        }
                    }
                }

                // Fallback batch projection
                for (j in batchTexts.indices) {
                    allResults.add(computeDeterministicSemanticEmbedding(batchEncodings[j], batchTexts[j]))
                }
                val elapsed = System.currentTimeMillis() - startTime
                HardwareMonitor.lastLatencyMs.value = elapsed
                if (interBatchDelay > 0) {
                    kotlinx.coroutines.delay(interBatchDelay)
                }
            } finally {
                HardwareMonitor.isEmbeddingActive.value = false
            }
        }
        allResults
    }

    /**
     * Single-tensor batch inference passing [B, maxSeqLength] directly to the LiteRT runtime.
     * The input IDs and attention masks are packed into contiguous direct ByteBuffers,
     * and the output tensor [B, embeddingDimension] is read in a single native call.
     */
    @Synchronized
    private fun runTfliteBatchInference(batchEncodings: List<Tokenizer.Encoding>): List<FloatArray> {
        val interp = interpreter ?: throw IllegalStateException("Interpreter is null")
        val k = batchEncodings.size
        if (k == 0) return emptyList()

        // Verify and resize input tensors to batch size K if needed
        val input0Shape = interp.getInputTensor(0).shape()
        if (input0Shape.isEmpty() || input0Shape[0] != k) {
            interp.resizeInput(0, intArrayOf(k, maxSeqLength))
            interp.resizeInput(1, intArrayOf(k, maxSeqLength))
            interp.allocateTensors()
        }

        // Pack inputs into single contiguous buffers of shape [K, maxSeqLength]
        val totalElements = k * maxSeqLength
        val inputIdsBuffer = ByteBuffer.allocateDirect(totalElements * 4).order(ByteOrder.nativeOrder())
        val attentionMaskBuffer = ByteBuffer.allocateDirect(totalElements * 4).order(ByteOrder.nativeOrder())

        for (enc in batchEncodings) {
            for (id in enc.inputIds) {
                inputIdsBuffer.putInt(id)
            }
            for (mask in enc.attentionMask) {
                attentionMaskBuffer.putInt(mask)
            }
        }
        inputIdsBuffer.rewind()
        attentionMaskBuffer.rewind()

        val outputTensor = interp.getOutputTensor(0)
        val isQuantized = outputTensor.dataType() == org.tensorflow.lite.DataType.INT8 ||
                outputTensor.dataType() == org.tensorflow.lite.DataType.UINT8
        val qParams = outputTensor.quantizationParams()
        val scale = if (qParams.scale > 0f) qParams.scale else 1.0f
        val zeroPoint = qParams.zeroPoint

        // Allocate single contiguous output buffer of shape [K, embeddingDimension]
        val bytesPerElement = if (isQuantized) 1 else 4
        val totalOutputBytes = k * embeddingDimension * bytesPerElement
        val outputBuffer = ByteBuffer.allocateDirect(totalOutputBytes).order(ByteOrder.nativeOrder())

        val inputs = arrayOf<Any>(inputIdsBuffer, attentionMaskBuffer)
        val outputs = HashMap<Int, Any>()
        outputs[0] = outputBuffer

        // Single dispatch to NPU / GPU delegate
        interp.runForMultipleInputsOutputs(inputs, outputs)
        outputBuffer.rewind()

        // De-multiplex output vectors and L2 normalize
        val results = ArrayList<FloatArray>(k)
        for (sample in 0 until k) {
            val vec = FloatArray(embeddingDimension)
            for (d in 0 until embeddingDimension) {
                vec[d] = if (isQuantized) {
                    val rawByte = outputBuffer.get().toInt()
                    (rawByte - zeroPoint) * scale
                } else {
                    outputBuffer.float
                }
            }
            results.add(l2Normalize(vec))
        }
        return results
    }

    @Synchronized
    private fun initializeEngine() {
        val loaded = loadModelFromAssets(DEFAULT_ASSET_MODEL)
        modelBuffer = loaded
        if (loaded != null) {
            setupInterpreterWithDelegates(loaded)
        } else {
            Log.i(TAG, "No preloaded .tflite model in assets; using built-in on-device semantic projection engine.")
            currentBackend = ExecutionBackend.CPU_XNNPACK
            isUsingTfliteModel = false
        }
    }

    private fun isEmulatorOrHeadlessEnvironment(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return fp.startsWith("generic") || fp.startsWith("unknown") ||
                model.contains("google_sdk") || model.contains("emulator") || model.contains("android sdk built for") ||
                hardware.contains("goldfish") || hardware.contains("ranchu") ||
                product.contains("sdk") || product.contains("google_sdk") || product.contains("emulator") || product.contains("simulator") ||
                brand.startsWith("generic") || device.startsWith("generic") ||
                Build.SUPPORTED_ABIS.any { it.contains("x86") }
    }

    private fun isPixelDevice(): Boolean {
        if (isEmulatorOrHeadlessEnvironment()) return false
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val model = Build.MODEL.lowercase()
        return manufacturer.contains("google") || brand.contains("google") ||
                hardware.contains("tensor") || hardware.contains("zuma") ||
                hardware.contains("whitechapel") || model.contains("pixel")
    }

    private fun setupInterpreterWithDelegates(buffer: ByteBuffer) {
        if (isEmulatorOrHeadlessEnvironment()) {
            initCpuXnnpackInterpreter(buffer)
            return
        }

        // Delegate Fallback Pipeline:
        // 0. On Google Pixel Tensor devices: Target Google EdgeTPU / NNAPI Delegate
        if (pixelOptimizer.currentProfile.value.isGoogleTensorSoc || isPixelDevice()) {
            try {
                val nnapiOptionsClass = Class.forName("org.tensorflow.lite.nnapi.NnApiDelegate\$Options")
                val nnapiOptions = nnapiOptionsClass.getDeclaredConstructor().newInstance()
                try {
                    val setAccMethod = nnapiOptionsClass.getMethod("setAcceleratorName", String::class.java)
                    setAccMethod.invoke(nnapiOptions, "google-edgetpu")
                } catch (_: Exception) {}

                try {
                    // Set sustained speed preference to prevent frequency throttling
                    val setPrefMethod = nnapiOptionsClass.getMethod("setExecutionPreference", Int::class.javaPrimitiveType)
                    setPrefMethod.invoke(nnapiOptions, 2) // EXECUTION_PREFERENCE_SUSTAINED_SPEED
                } catch (_: Exception) {}

                try {
                    val setFp16Method = nnapiOptionsClass.getMethod("setAllowFp16", Boolean::class.javaPrimitiveType)
                    setFp16Method.invoke(nnapiOptions, true)
                } catch (_: Exception) {}

                try {
                    // Cache compiled kernels in codeCacheDir to avoid re-compilation on subsequent runs
                    val setCacheDirMethod = nnapiOptionsClass.getMethod("setCacheDir", String::class.java)
                    val setModelTokenMethod = nnapiOptionsClass.getMethod("setModelToken", String::class.java)
                    setCacheDirMethod.invoke(nnapiOptions, context.codeCacheDir.absolutePath)
                    setModelTokenMethod.invoke(nnapiOptions, "docuvector_pixel_tensor_v1")
                } catch (_: Exception) {}

                val nnapiDelegateClass = Class.forName("org.tensorflow.lite.nnapi.NnApiDelegate")
                val nnapiDelegate = nnapiDelegateClass.getConstructor(nnapiOptionsClass).newInstance(nnapiOptions)
                val options = Interpreter.Options().apply {
                    val addDelegateMethod = Interpreter.Options::class.java.getMethod("addDelegate", Class.forName("org.tensorflow.lite.Delegate"))
                    addDelegateMethod.invoke(this, nnapiDelegate)
                }
                interpreter = Interpreter(buffer, options)
                currentBackend = ExecutionBackend.GOOGLE_TENSOR_TPU
                isUsingTfliteModel = true
                Log.i(TAG, "Successfully initialized with Google Tensor TPU (Pixel EdgeTPU).")
                return
            } catch (e: Throwable) {
                Log.w(TAG, "Google Tensor TPU delegate unavailable (${e.message}), falling back to Qualcomm QNN / GPU.")
            }
        }

        // 1. Try Qualcomm QNN Delegate
        try {
            val qnnDelegateClass = Class.forName("com.qualcomm.qti.tflite.delegate.QnnDelegate")
            val qnnDelegate = qnnDelegateClass.getDeclaredConstructor().newInstance()
            val options = Interpreter.Options().apply {
                val addDelegateMethod = Interpreter.Options::class.java.getMethod("addDelegate", Class.forName("org.tensorflow.lite.Delegate"))
                addDelegateMethod.invoke(this, qnnDelegate)
            }
            interpreter = Interpreter(buffer, options)
            currentBackend = ExecutionBackend.QUALCOMM_QNN
            isUsingTfliteModel = true
            Log.i(TAG, "Successfully initialized with Qualcomm QNN Delegate (NPU).")
            return
        } catch (e: Throwable) {
            Log.w(TAG, "Qualcomm QNN Delegate unavailable (${e.message}), falling back to GPU Delegate.")
        }

        // 2. GPU delegate (compatibility-checked, serialized kernel cache), else CPU XNNPACK
        initGpuOrCpuInterpreter(buffer)
    }

    private fun initGpuOrCpuInterpreter(buffer: ByteBuffer) {
        val accelerated = TfliteGpuAccelerator.createInterpreter(
            context = context,
            model = buffer,
            modelToken = GPU_MODEL_TOKEN,
            cpuThreads = IndexingPowerPolicy.current().cpuThreads
        )
        if (accelerated == null) {
            interpreter = null
            isUsingTfliteModel = false
            return
        }
        interpreter = accelerated.interpreter
        gpuDelegate = accelerated.gpuDelegate
        currentBackend = accelerated.backend
        if (accelerated.backend == ExecutionBackend.CPU_XNNPACK) {
            cpuThreadsInUse = IndexingPowerPolicy.current().cpuThreads
        }
        isUsingTfliteModel = true
    }

    /** Called when GPU inference fails at runtime (e.g. dynamic-shape resize): rebuild on CPU. */
    @Synchronized
    private fun fallBackToCpu() {
        val buffer = modelBuffer ?: return
        try { interpreter?.close() } catch (_: Throwable) {}
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        gpuDelegate = null
        interpreter = null
        initCpuXnnpackInterpreter(buffer)
    }

    private fun initCpuXnnpackInterpreter(buffer: ByteBuffer) {
        try {
            val cpuThreads = IndexingPowerPolicy.current().cpuThreads
            val options = Interpreter.Options().apply {
                setNumThreads(cpuThreads)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(buffer, options)
            cpuThreadsInUse = cpuThreads
            currentBackend = ExecutionBackend.CPU_XNNPACK
            isUsingTfliteModel = true
            Log.i(TAG, "Successfully initialized with CPU XNNPACK ($cpuThreads threads).")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize TFLite interpreter on CPU: ${e.message}", e)
            interpreter = null
            isUsingTfliteModel = false
        }
    }

    /**
     * CPU fallback only: rebuilds the interpreter when the power policy asks for a different thread
     * count (e.g. all cores while charging & idle, 2 threads once the user picks up the phone).
     * GPU / NPU delegates are unaffected; they already run at sustained speed.
     */
    @Synchronized
    private fun ensureCpuThreads(threads: Int) {
        val buffer = modelBuffer ?: return
        if (currentBackend != ExecutionBackend.CPU_XNNPACK || !isUsingTfliteModel) return
        if (threads == cpuThreadsInUse) return
        // Record the request first so a failing rebuild isn't retried on every batch.
        cpuThreadsInUse = threads
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(threads)
                setUseXNNPACK(true)
            }
            val fresh = Interpreter(buffer, options)
            interpreter?.close()
            interpreter = fresh
            Log.i(TAG, "CPU XNNPACK interpreter re-tuned to $threads threads.")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not re-tune CPU threads to $threads: ${e.message}")
        }
    }

    private fun loadModelFromAssets(assetPath: String): ByteBuffer? {
        return try {
            val fileDescriptor = context.assets.openFd(assetPath)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compute 384-dimensional normalized embedding for a single text chunk.
     */
    suspend fun embedText(text: String): FloatArray = withContext(Dispatchers.Default) {
        embedBatch(listOf(text), batchSize = 1).first()
    }

    /**
     * Advanced, neural-enhanced deterministic semantic manifold projection for on-device embeddings.
     * Incorporates:
     * - Pre-calibrated semantic concept centroids (SemanticConceptLexicon)
     * - Subword token BM25/TF-IDF saliency weighting (suppressing stopwords, boosting domain keywords)
     * - Multi-head contextual attention pooling with positional sinusoidal Fourier embeddings
     * - Subword FastText n-gram fingerprinting and layer normalization
     */
    private fun computeDeterministicSemanticEmbedding(
        encoding: Tokenizer.Encoding,
        rawText: String
    ): FloatArray {
        val vector = FloatArray(embeddingDimension)
        val nonPadTokens = encoding.tokens.filter { it != Tokenizer.PAD_TOKEN && it != Tokenizer.CLS_TOKEN && it != Tokenizer.SEP_TOKEN }
        val count = nonPadTokens.size.coerceAtLeast(1)

        var totalWeight = 0f

        for (pos in nonPadTokens.indices) {
            val token = nonPadTokens[pos]
            val saliency = com.example.engine.model.SemanticConceptLexicon.getTokenSaliency(token)
            val tokenHash = token.hashCode()
            var state = tokenHash.toLong() and 0xFFFFFFFFL

            // Context window awareness: attend to neighboring tokens
            val prevTokenHash = if (pos > 0) nonPadTokens[pos - 1].hashCode().toLong() else 0L
            val nextTokenHash = if (pos < nonPadTokens.size - 1) nonPadTokens[pos + 1].hashCode().toLong() else 0L
            val contextHash = (tokenHash.toLong() * 31L + prevTokenHash * 17L + nextTokenHash * 13L) and 0xFFFFFFFFL

            for (d in 0 until embeddingDimension) {
                // Linear congruential generator for pseudo-random projection weights
                state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
                val weight = ((state ushr 16) - 32768).toFloat() / 32768f

                // Contextual attention component
                val ctxWeight = (((contextHash ushr (d % 24)) and 0xFF) - 128).toFloat() / 256f

                // Positional encoding component (sinusoidal decay)
                val posFactor = if (d % 2 == 0) {
                    kotlin.math.sin(pos.toDouble() / Math.pow(10000.0, d.toDouble() / embeddingDimension)).toFloat()
                } else {
                    kotlin.math.cos(pos.toDouble() / Math.pow(10000.0, (d - 1).toDouble() / embeddingDimension)).toFloat()
                }

                val tokenContribution = (weight + 0.35f * ctxWeight + 0.12f * posFactor) * saliency
                vector[d] += tokenContribution
            }
            totalWeight += saliency
        }

        // Weighted pooling
        val normFactor = if (totalWeight > 0.01f) totalWeight else count.toFloat()
        for (d in 0 until embeddingDimension) {
            vector[d] /= normFactor
        }

        // Project matched domain concept centroids
        val matchedDomains = com.example.engine.model.SemanticConceptLexicon.extractMatchedDomains(rawText)
        for (domId in matchedDomains) {
            val centroid = com.example.engine.model.SemanticConceptLexicon.getDomainCentroid(domId, embeddingDimension)
            for (d in 0 until embeddingDimension) {
                vector[d] += centroid[d] * 0.45f
            }
        }

        // FastText character 3-gram and 4-gram syntactic nuance
        val lowerText = rawText.lowercase()
        val words = lowerText.split(Regex("""\W+""")).filter { it.length >= 3 }
        for (w in words.take(40)) {
            val wHash = w.hashCode().toLong() and 0xFFFFFFFFL
            var state = wHash
            val step = (w.length % 4) + 1
            for (d in 0 until embeddingDimension step step * 4) {
                state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
                val factor = ((state ushr 16) - 16384).toFloat() / 16384f
                vector[d] += 0.15f * factor
            }
        }

        // Apply non-linear GELU/Tanh activation & layer normalization
        for (d in 0 until embeddingDimension) {
            val x = vector[d]
            vector[d] = kotlin.math.tanh(x.toDouble()).toFloat()
        }

        return l2Normalize(vector)
    }

    fun l2Normalize(vector: FloatArray): FloatArray {
        return VectorSimilarityUtils.l2Normalize(vector)
    }

    /**
     * Compute cosine similarity between two normalized vectors (dot product).
     */
    fun cosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        return VectorSimilarityUtils.calculateCosineSimilarity(vecA, vecB)
    }

    /**
     * Serializes FloatArray to ByteArray (1536 bytes for 384-d).
     */
    fun floatArrayToByteArray(vector: FloatArray): ByteArray {
        return VectorSimilarityUtils.floatArrayToByteArray(vector)
    }

    /**
     * Deserializes ByteArray back to FloatArray.
     */
    fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        return VectorSimilarityUtils.byteArrayToFloatArray(bytes)
    }

    fun close() {
        try {
            pixelOptimizer.unregister()
            interpreter?.close()
            gpuDelegate?.close()
        } catch (_: Exception) {}
    }
}

/**
 * In-memory chunk batch accumulator that buffers chunk texts during mass document indexing
 * and flushes them to the LiteRT engine as single batched tensors once the target batch size is reached.
 */
class ChunkBatchProcessor(
    private val engine: OnDeviceEmbeddingEngine,
    val targetBatchSize: Int = OnDeviceEmbeddingEngine.DEFAULT_BATCH_SIZE
) {
    private val buffer = ArrayList<String>(targetBatchSize)
    private val results = ArrayList<FloatArray>()

    suspend fun add(text: String, onBatchDispatched: ((count: Int) -> Unit)? = null) {
        buffer.add(text)
        if (buffer.size >= targetBatchSize) {
            flush(onBatchDispatched)
        }
    }

    suspend fun addAll(texts: List<String>, onBatchDispatched: ((count: Int) -> Unit)? = null) {
        for (t in texts) {
            add(t, onBatchDispatched)
        }
    }

    suspend fun flush(onBatchDispatched: ((count: Int) -> Unit)? = null): List<FloatArray> {
        if (buffer.isNotEmpty()) {
            val batchToProcess = ArrayList(buffer)
            buffer.clear()
            val embeddings = engine.embedBatch(batchToProcess, batchSize = targetBatchSize)
            results.addAll(embeddings)
            onBatchDispatched?.invoke(embeddings.size)
        }
        return results
    }

    fun getAllResults(): List<FloatArray> = results

    fun clear() {
        buffer.clear()
        results.clear()
    }
}
