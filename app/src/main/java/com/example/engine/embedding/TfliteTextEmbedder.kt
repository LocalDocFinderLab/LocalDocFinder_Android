package com.example.engine.embedding

import android.content.Context
import android.util.Log
import com.example.engine.ExecutionBackend
import com.example.engine.HardwareMonitor
import com.example.engine.IndexingPowerPolicy
import com.example.engine.TfliteGpuAccelerator
import com.example.engine.model.EmbeddingModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Local sentence-embedding pipeline: text → WordPiece ids → TensorFlow Lite transformer → pooled,
 * L2-normalised vector. Everything happens on the device; there is no network access.
 *
 * It adapts to however the model was exported:
 *  - inputs are matched by tensor name (`input_ids`, `attention_mask`, `token_type_ids`), falling back to
 *    the Hugging Face positional order; `int32` and `int64` are both accepted;
 *  - a fixed sequence length is padded to, a dynamic one is resized to each text's real length;
 *  - the output may be per-token hidden states `[1, L, D]` (pooled here with the model's own pooling) or an
 *    already-pooled `[1, D]`; float and int8/uint8-quantised outputs are both handled.
 *
 * The interpreter is not thread-safe, so every public call is serialised.
 */
class TfliteTextEmbedder private constructor(
    private val context: Context,
    val model: EmbeddingModelType,
    private val tokenizer: WordPieceTokenizer,
    private val modelBuffer: ByteBuffer,
    private var accelerated: TfliteGpuAccelerator.AcceleratedInterpreter,
    private var layout: Layout,
    private var threads: Int
) : AutoCloseable {

    companion object {
        private const val TAG = "TfliteTextEmbedder"

        /** Longest sequence run when the model's length is dynamic (keeps attention cost bounded). */
        private const val DYNAMIC_MAX_LENGTH = 512

        /**
         * Loads [model] from [store]. Returns null (and logs why) when the files are missing or the
         * graph is not a usable sentence embedder, so callers can fall back to the built-in embedder.
         *
         * GPU is off by default: transformer graphs with int8 weights mostly run on the CPU anyway under the
         * GPU delegate, and XNNPACK on the CPU is faster and more predictable for BERT-style models.
         */
        fun create(
            context: Context,
            model: EmbeddingModelType,
            store: EmbeddingModelStore,
            allowGpu: Boolean = false,
            threads: Int = IndexingPowerPolicy.current().cpuThreads
        ): TfliteTextEmbedder? {
            if (model.isBuiltIn || !store.isInstalled(model)) return null
            return try {
                val tokenizer = store.openVocab(model).use { WordPieceTokenizer.fromVocab(it) }
                val buffer = store.openModel(model)
                val accelerated = build(context, model, buffer, allowGpu, threads)
                val layout = try {
                    detectLayout(accelerated.interpreter, model).also {
                        check(accelerated.interpreter.getOutputTensor(it.outputIndex).shape().last() == model.dimensions)
                    }
                } catch (t: Throwable) {
                    accelerated.close()
                    throw t
                }
                Log.i(TAG, "${model.id} loaded on ${accelerated.backend.displayName} (seq=${layout.fixedSeqLength}, outRank=${layout.outputRank})")
                TfliteTextEmbedder(context, model, tokenizer, buffer, accelerated, layout, threads)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not load ${model.id}: ${t.message}")
                null
            }
        }

        private fun build(
            context: Context,
            model: EmbeddingModelType,
            buffer: ByteBuffer,
            gpu: Boolean,
            threads: Int
        ): TfliteGpuAccelerator.AcceleratedInterpreter =
            TfliteGpuAccelerator.createInterpreter(
                context = context,
                model = buffer,
                modelToken = "${model.id}_v1",
                cpuThreads = threads,
                allowGpu = gpu
            ) ?: throw IllegalStateException("could not create a TFLite interpreter for ${model.id}")

        /** Wraps ints as the native-endian tensor payload TFLite expects. */
        internal fun intTensorBuffer(values: IntArray, type: DataType): ByteBuffer = when (type) {
            DataType.INT32 -> ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).also { b ->
                values.forEach { b.putInt(it) }
                b.rewind()
            }
            DataType.INT64 -> ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder()).also { b ->
                values.forEach { b.putLong(it.toLong()) }
                b.rewind()
            }
            else -> throw IllegalStateException("Unsupported id tensor type $type")
        }

        private fun detectLayout(interp: Interpreter, model: EmbeddingModelType): Layout {
            val count = interp.inputTensorCount
            var ids = -1
            var mask = -1
            var type = -1
            for (i in 0 until count) {
                val name = interp.getInputTensor(i).name().lowercase()
                when {
                    "type" in name || "segment" in name -> type = i
                    "mask" in name -> mask = i
                    "id" in name || "token" in name -> if (ids < 0) ids = i
                }
            }
            if (ids < 0) { // names are not informative: assume Hugging Face order
                ids = 0
                mask = if (count > 1) 1 else -1
                type = if (count > 2) 2 else -1
            }

            val signature = interp.getInputTensor(ids).shapeSignature()
            check(signature.size == 2) { "input_ids must be rank 2 [batch, seq], got ${signature.toList()}" }
            check(signature[0] <= 1) { "model was exported with batch size ${signature[0]}; export with batch 1" }
            val fixedSeq = signature[1]

            if (fixedSeq < 0) {
                for (i in 0 until count) interp.resizeInput(i, intArrayOf(1, 8))
            }
            interp.allocateTensors()

            // Prefer per-token hidden states [1, L, D] (pooled here, correctly for this model); the BERT
            // "pooler_output" [1, D] is a different head not trained for retrieval, so [1, D] ranks last.
            var chosen = -1
            var chosenRank = 0
            for (i in 0 until interp.outputTensorCount) {
                val shape = interp.getOutputTensor(i).shape()
                if (shape.size == 3 && shape.last() == model.dimensions) {
                    chosen = i; chosenRank = 3; break
                }
            }
            if (chosen < 0) {
                for (i in 0 until interp.outputTensorCount) {
                    val shape = interp.getOutputTensor(i).shape()
                    if (shape.size == 2 && shape.last() == model.dimensions) {
                        chosen = i; chosenRank = 2; break
                    }
                }
            }
            check(chosen >= 0) {
                "no output of size ${model.dimensions} found; outputs: " +
                    (0 until interp.outputTensorCount).joinToString { interp.getOutputTensor(it).shape().toList().toString() }
            }
            return Layout(ids, mask, type, fixedSeq, chosen, chosenRank)
        }
    }

    /** Which input tensor carries what, and the shape facts needed to feed the model. */
    private class Layout(
        val idsIndex: Int,
        val maskIndex: Int,
        val typeIndex: Int,
        /** Fixed sequence length, or -1 when the model accepts any length. */
        val fixedSeqLength: Int,
        val outputIndex: Int,
        val outputRank: Int
    )

    private var allocatedSeqLength = if (layout.fixedSeqLength > 0) layout.fixedSeqLength else -1

    val backend: ExecutionBackend get() = accelerated.backend
    val dimension: Int get() = model.dimensions

    // ---- Public API ---------------------------------------------------------------------------------------

    /** Embeds one text. [isQuery] selects the model's query or document prefix. */
    @Synchronized
    fun embed(text: String, isQuery: Boolean): FloatArray {
        val prefixed = (if (isQuery) model.queryPrefix else model.documentPrefix) + text
        val maxLength = if (layout.fixedSeqLength > 0) layout.fixedSeqLength else DYNAMIC_MAX_LENGTH
        val encoding = tokenizer.encode(prefixed, maxLength)
        return try {
            infer(encoding)
        } catch (t: Throwable) {
            if (accelerated.backend != ExecutionBackend.GPU_DELEGATE) throw t
            Log.w(TAG, "GPU inference failed (${t.message}); rebuilding on CPU")
            rebuild(allowGpu = false, threads = threads)
            infer(encoding)
        }
    }

    /**
     * Embeds many texts, honouring the indexing power policy (batch size, pacing, thread count) and the
     * user's pause button, exactly like the built-in embedder so the "indexing slowed" UI stays accurate.
     */
    suspend fun embedBatch(texts: List<String>, isQuery: Boolean, batchSize: Int): List<FloatArray> =
        withContext(Dispatchers.Default) {
            val results = ArrayList<FloatArray>(texts.size)
            var offset = 0
            while (offset < texts.size) {
                HardwareMonitor.checkPausePoint()
                val speed = IndexingPowerPolicy.current()
                retune(speed.cpuThreads)
                val end = minOf(offset + minOf(batchSize, speed.batchSize).coerceAtLeast(1), texts.size)
                val started = System.currentTimeMillis()
                HardwareMonitor.isEmbeddingActive.value = true
                try {
                    for (i in offset until end) results.add(embed(texts[i], isQuery))
                } finally {
                    HardwareMonitor.isEmbeddingActive.value = false
                }
                HardwareMonitor.lastLatencyMs.value = System.currentTimeMillis() - started
                offset = end
                if (speed.interBatchDelayMs > 0 && offset < texts.size) delay(speed.interBatchDelayMs)
            }
            results
        }

    @Synchronized
    override fun close() {
        accelerated.close()
    }

    // ---- Inference ----------------------------------------------------------------------------------------

    private fun infer(encoding: WordPieceTokenizer.Encoding): FloatArray {
        val interp = accelerated.interpreter
        val seqLength = if (layout.fixedSeqLength > 0) layout.fixedSeqLength else encoding.length
        if (seqLength != allocatedSeqLength) {
            for (i in 0 until interp.inputTensorCount) interp.resizeInput(i, intArrayOf(1, seqLength))
            interp.allocateTensors()
            allocatedSeqLength = seqLength
        }

        val ids = IntArray(seqLength) { if (it < encoding.length) encoding.ids[it] else tokenizer.padId }
        val mask = IntArray(seqLength) { if (it < encoding.length) 1 else 0 }

        val inputs = arrayOfNulls<Any>(interp.inputTensorCount)
        for (i in inputs.indices) {
            val tensor = interp.getInputTensor(i)
            val values = when (i) {
                layout.idsIndex -> ids
                layout.maskIndex -> mask
                else -> IntArray(tensor.numElements()) // token_type_ids (single segment) or any extra input: zeros
            }
            inputs[i] = intTensorBuffer(values, tensor.dataType())
        }

        val outTensor = interp.getOutputTensor(layout.outputIndex)
        val output = ByteBuffer.allocateDirect(outTensor.numElements() * bytesPerElement(outTensor.dataType()))
            .order(ByteOrder.nativeOrder())
        interp.runForMultipleInputsOutputs(inputs, mapOf(layout.outputIndex to output as Any))
        output.rewind()

        val raw = readFloats(output, outTensor.dataType(), outTensor.numElements(), outTensor.quantizationParams().scale, outTensor.quantizationParams().zeroPoint)
        return if (layout.outputRank == 3) {
            EmbeddingPooling.pool(raw, mask, model.dimensions, model.pooling)
        } else {
            EmbeddingPooling.l2Normalize(raw.copyOf(model.dimensions))
        }
    }

    private fun readFloats(buf: ByteBuffer, type: DataType, count: Int, scale: Float, zeroPoint: Int): FloatArray {
        val out = FloatArray(count)
        when (type) {
            DataType.FLOAT32 -> buf.asFloatBuffer().get(out)
            DataType.INT8 -> for (i in 0 until count) out[i] = (buf.get().toInt() - zeroPoint) * scale
            DataType.UINT8 -> for (i in 0 until count) out[i] = ((buf.get().toInt() and 0xFF) - zeroPoint) * scale
            else -> throw IllegalStateException("Unsupported output tensor type $type")
        }
        return out
    }

    private fun bytesPerElement(type: DataType) = when (type) {
        DataType.FLOAT32 -> 4
        DataType.INT8, DataType.UINT8 -> 1
        else -> throw IllegalStateException("Unsupported output tensor type $type")
    }

    // ---- Interpreter lifecycle ----------------------------------------------------------------------------

    @Synchronized
    private fun rebuild(allowGpu: Boolean, threads: Int) {
        // Build the replacement first so a failure leaves the working interpreter in place.
        val fresh = build(context, model, modelBuffer, allowGpu, threads)
        val freshLayout = try {
            detectLayout(fresh.interpreter, model)
        } catch (t: Throwable) {
            fresh.close()
            throw t
        }
        accelerated.close()
        accelerated = fresh
        layout = freshLayout
        this.threads = threads
        allocatedSeqLength = if (layout.fixedSeqLength > 0) layout.fixedSeqLength else -1
    }

    /** Applies a changed CPU thread budget from the power policy (a no-op on GPU or when unchanged). */
    @Synchronized
    private fun retune(wantedThreads: Int) {
        if (accelerated.backend != ExecutionBackend.CPU_XNNPACK || wantedThreads == threads) return
        threads = wantedThreads // record first so a failing rebuild is not retried every batch
        try {
            rebuild(allowGpu = false, threads = wantedThreads)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not re-tune ${model.id} to $wantedThreads threads: ${t.message}")
        }
    }
}
