package com.example.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.nio.ByteBuffer

/**
 * Central place for TensorFlow Lite hardware acceleration (GPU delegate with CPU/XNNPACK fallback).
 *
 * The GPU delegate is only used when [CompatibilityList] reports the device as supported, with the
 * per-device best options (OpenCL/OpenGL backend, precision, etc.). Compiled GPU kernels are
 * serialized into the app's code cache so subsequent launches skip shader compilation.
 * Any failure (missing native libs, unsupported ops, emulator) degrades to CPU + XNNPACK.
 */
object TfliteGpuAccelerator {
    private const val TAG = "TfliteGpuAccelerator"

    /** An interpreter together with the backend it actually runs on and any delegate to close. */
    class AcceleratedInterpreter(
        val interpreter: Interpreter,
        val backend: ExecutionBackend,
        val gpuDelegate: GpuDelegate?
    ) {
        fun close() {
            try { interpreter.close() } catch (_: Throwable) {}
            try { gpuDelegate?.close() } catch (_: Throwable) {}
        }
    }

    /** True when the GPU delegate classes load and this device is on TFLite's GPU allow-list. */
    fun isGpuSupported(): Boolean = try {
        CompatibilityList().use { it.isDelegateSupportedOnThisDevice }
    } catch (t: Throwable) {
        Log.i(TAG, "GPU compatibility check unavailable: ${t.message}")
        false
    }

    /**
     * Creates a [GpuDelegate] tuned for this device, or null if the GPU can't be used.
     * @param modelToken unique, stable id for the model (keys the serialized kernel cache).
     */
    fun createGpuDelegate(context: Context, modelToken: String): GpuDelegate? {
        return try {
            CompatibilityList().use { compat ->
                if (!compat.isDelegateSupportedOnThisDevice) return null
                val options = compat.bestOptionsForThisDevice.apply {
                    setPrecisionLossAllowed(true) // FP16 on GPU: ~2x faster, negligible embedding drift
                    setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    val dir = File(context.codeCacheDir, "tflite_gpu").apply { mkdirs() }
                    setSerializationParams(dir.absolutePath, modelToken)
                }
                GpuDelegate(options)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate creation failed: ${t.message}")
            null
        }
    }

    fun cpuOptions(threads: Int) = Interpreter.Options().apply {
        setNumThreads(threads.coerceAtLeast(1))
        setUseXNNPACK(true)
    }

    /** GPU first, CPU/XNNPACK second. Returns null only if even the CPU interpreter fails. */
    fun createInterpreter(
        context: Context,
        model: ByteBuffer,
        modelToken: String,
        cpuThreads: Int,
        allowGpu: Boolean = true
    ): AcceleratedInterpreter? {
        if (allowGpu) {
            val delegate = createGpuDelegate(context, modelToken)
            if (delegate != null) {
                try {
                    val interpreter = Interpreter(model, Interpreter.Options().addDelegate(delegate))
                    Log.i(TAG, "Interpreter running on GPU delegate.")
                    return AcceleratedInterpreter(interpreter, ExecutionBackend.GPU_DELEGATE, delegate)
                } catch (t: Throwable) {
                    Log.w(TAG, "Interpreter rejected GPU delegate (${t.message}); using CPU.")
                    try { delegate.close() } catch (_: Throwable) {}
                }
            }
        }
        return try {
            AcceleratedInterpreter(
                Interpreter(model, cpuOptions(cpuThreads)),
                ExecutionBackend.CPU_XNNPACK,
                null
            ).also { Log.i(TAG, "Interpreter running on CPU (XNNPACK, $cpuThreads threads).") }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create CPU interpreter: ${t.message}", t)
            null
        }
    }
}
