package com.example.engine

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import java.io.File

/**
 * What this phone is made of: processor, GPU and neural accelerator (NPU / TPU), with versions where Android
 * exposes them. Shown in the hardware dashboard in every build. Every probe is best effort: a value Android
 * does not report is "Not reported" rather than a guess.
 */
object DeviceHardwareInfo {
    private const val TAG = "DeviceHardwareInfo"
    const val UNKNOWN = "Not reported"

    data class Info(
        val device: String,
        val socName: String,
        val cpuSummary: String,
        val abis: String,
        val gpuName: String,
        val gpuApis: String,
        val gpuDelegateSupported: Boolean,
        val npuName: String,
        val npuDetail: String
    )

    @Volatile private var cached: Info? = null

    /** Cached after the first call. Opens a throwaway OpenGL context, so call it off the main thread. */
    fun get(context: Context): Info =
        cached ?: synchronized(this) { cached ?: read(context.applicationContext).also { cached = it } }

    private fun read(context: Context): Info {
        val gl = readGlStrings()
        val soc = socName()
        val gpuApis = listOfNotNull(
            gl?.version?.takeIf { it.isNotBlank() },
            vulkanVersion(context)?.let { "Vulkan $it" }
        ).joinToString(" · ").ifBlank { UNKNOWN }
        val (npuName, npuDetail) = npu(context)
        return Info(
            device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})",
            socName = soc,
            cpuSummary = cpuSummary(),
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            gpuName = gl?.renderer?.takeIf { it.isNotBlank() } ?: UNKNOWN,
            gpuApis = gpuApis,
            gpuDelegateSupported = TfliteGpuAccelerator.isGpuSupported(),
            npuName = npuName,
            npuDetail = npuDetail
        )
    }

    private fun socName(): String {
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val maker = Build.SOC_MANUFACTURER
            val model = Build.SOC_MODEL
            if (maker != Build.UNKNOWN && maker.isNotBlank()) parts += maker
            if (model != Build.UNKNOWN && model.isNotBlank()) parts += model
        }
        if (parts.isEmpty()) parts += listOf(Build.HARDWARE, Build.BOARD).filter { it.isNotBlank() && it != Build.UNKNOWN }.distinct()
        val tensor = tensorGeneration(Build.HARDWARE) ?: tensorGeneration(Build.BOARD)
        return (parts.joinToString(" ").ifBlank { UNKNOWN }) + (tensor?.let { " ($it)" } ?: "")
    }

    /** Google Tensor generation from the board code (Pixel 6 = gs101 ... Pixel 9 = zumapro). */
    internal fun tensorGeneration(code: String): String? = when (code.lowercase()) {
        "gs101", "oriole", "raven", "bluejay" -> "Google Tensor G1"
        "gs201", "panther", "cheetah", "lynx", "felix", "tangorpro" -> "Google Tensor G2"
        "zuma", "shiba", "husky", "akita" -> "Google Tensor G3"
        "zumapro", "tokay", "caiman", "komodo", "comet" -> "Google Tensor G4"
        "laguna" -> "Google Tensor G5"
        else -> null
    }

    /** "8 cores · up to 2.8 GHz", read from the kernel's per-core frequency files. */
    private fun cpuSummary(): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val maxKhz = try {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                ?.mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf { f -> f.canRead() }?.readText()?.trim()?.toLongOrNull() }
                ?.maxOrNull()
        } catch (_: Exception) { null }
        return if (maxKhz != null) "$cores cores · up to ${"%.2f".format(maxKhz / 1_000_000.0)} GHz" else "$cores cores"
    }

    private class GlStrings(val renderer: String, val version: String)

    private fun readGlStrings(): GlStrings? {
        var display = EGL14.EGL_NO_DISPLAY
        var ctx = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, v, 0, v, 1)) return null
            val cfgAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, configs, 0, 1, n, 0) || n[0] == 0) return null
            ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return null
            return GlStrings(
                renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
                version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            )
        } catch (t: Throwable) {
            Log.i(TAG, "GPU probe unavailable: ${t.message}")
            return null
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {}
        }
    }

    /** Vulkan version as "1.3" from the package manager's encoded feature version, or null without Vulkan. */
    private fun vulkanVersion(context: Context): String? = try {
        context.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.let { "${(it.version shr 22) and 0x3FF}.${(it.version shr 12) and 0x3FF}" }
    } catch (_: Throwable) { null }

    /**
     * Android has no public call that names the NPU, so this reports what is known: a Google Tensor TPU by
     * board code, otherwise the Android Neural Networks API level (the interface NPUs are reached through).
     */
    private fun npu(context: Context): Pair<String, String> {
        val tensor = tensorGeneration(Build.HARDWARE) ?: tensorGeneration(Build.BOARD)
        val nnapi = when {
            Build.VERSION.SDK_INT >= 35 -> "NNAPI 1.3 (deprecated; LiteRT uses vendor delegates)"
            Build.VERSION.SDK_INT >= 30 -> "NNAPI 1.3"
            Build.VERSION.SDK_INT >= 29 -> "NNAPI 1.2"
            else -> "NNAPI 1.1"
        }
        val hasNnapiFeature = try {
            context.packageManager.hasSystemFeature("android.hardware.neuralnetworks")
        } catch (_: Throwable) { false }
        return if (tensor != null) {
            "Google EdgeTPU ($tensor)" to "$nnapi · Tensor Processing Unit on the SoC"
        } else {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
            val label = if (hasNnapiFeature) "Neural accelerator via NNAPI" else "No dedicated NPU reported"
            label to "$nnapi · ${soc.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE}"
        }
    }
}
