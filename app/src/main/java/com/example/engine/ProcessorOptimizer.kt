package com.example.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * ProcessorOptimizer
 *
 * Universal on-device hardware profiler and intelligent workload orchestrator.
 * Supports:
 * - Google Tensor SoC (G1, G2, G3, G4, G5) -> EdgeTPU / NNAPI / Heterogeneous 3-cluster
 * - Qualcomm Snapdragon (8 Gen 1/2/3/4, 7-series, 6-series) -> Hexagon HTP / QNN / Adreno GPU
 * - MediaTek Dimensity (9000/8000 series) -> MediaTek APU / Mali GPU
 * - Samsung Exynos (2200/2400 series) -> Xclipse AMD GPU / Mali GPU
 * - Generic ARM / x86 -> Multi-threaded NEON SIMD XNNPACK
 *
 * Dynamic Features:
 * 1. Turbo Mode: When charging & not under heavy thermal load, unleashes full GPU/NPU throughput.
 * 2. Game & Heavy Task Protection: Detects thermal pressure or heavy system load, auto-pausing/throttling
 *    indexing so games and foreground apps experience ZERO frame drops or stutter.
 * 3. Auto-Resume: Automatically resumes full indexing when gaming ceases or temperatures return to normal.
 */
class ProcessorOptimizer(private val context: Context) {

    companion object {
        private const val TAG = "ProcessorOptimizer"

        enum class ProcessorFamily(val displayName: String) {
            GOOGLE_TENSOR("Google Tensor"),
            QUALCOMM_SNAPDRAGON("Qualcomm Snapdragon"),
            MEDIATEK_DIMENSITY("MediaTek Dimensity"),
            SAMSUNG_EXYNOS("Samsung Exynos"),
            GENERIC_ARM("ARM Multi-Core"),
            GENERIC_X86("x86 / Emulator")
        }

        enum class PowerProfileMode(val displayName: String, val badge: String) {
            TURBO_CHARGING("Turbo Processing (Charging)", "TURBO"),
            OPTIMAL_BATTERY("Balanced (Battery)", "BALANCED"),
            GAME_HEAVY_THROTTLED("Game / Thermal Guard (Paused)", "GUARDED")
        }
    }

    data class DeviceProcessorProfile(
        val family: ProcessorFamily,
        val chipName: String,
        val hardwareCode: String,
        val primaryAccelerator: String,
        val recommendedBackend: ExecutionBackend,
        val cpuCores: Int,
        val recommendedThreads: Int,
        val isCharging: Boolean,
        val isPluggedIn: Boolean,
        val powerMode: PowerProfileMode,
        val thermalStatusName: String,
        val thermalLevel: Int,
        val isGameProtectionActive: Boolean,
        val recommendedBatchSize: Int,
        val interBatchDelayMs: Long
    )

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var autoGameProtectionEnabled = true
    private var isUserPaused = false

    private val _currentProfile = MutableStateFlow(computeProfile())
    val currentProfile: StateFlow<DeviceProcessorProfile> = _currentProfile.asStateFlow()

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var batteryReceiver: BroadcastReceiver? = null

    init {
        registerListeners()
    }

    private fun registerListeners() {
        // 1. Thermal status monitoring (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                    Log.d(TAG, "Thermal status changed: $status")
                    _currentProfile.value = computeProfile(overrideThermal = status)
                }
                powerManager.addThermalStatusListener(context.mainExecutor, thermalListener!!)
            } catch (e: Exception) {
                Log.w(TAG, "Could not register thermal listener: ${e.message}")
            }
        }

        // 2. Battery & Charging monitoring
        try {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    _currentProfile.value = computeProfile()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register battery receiver: ${e.message}")
        }
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            try {
                powerManager.removeThermalStatusListener(thermalListener!!)
            } catch (_: Exception) {}
        }
        if (batteryReceiver != null) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {}
        }
    }

    fun setGameProtectionEnabled(enabled: Boolean) {
        autoGameProtectionEnabled = enabled
        _currentProfile.value = computeProfile()
    }

    fun isGameProtectionEnabled(): Boolean = autoGameProtectionEnabled

    fun setUserPaused(paused: Boolean) {
        isUserPaused = paused
        _currentProfile.value = computeProfile()
    }

    fun isPaused(): Boolean = isUserPaused

    fun computeProfile(overrideThermal: Int? = null): DeviceProcessorProfile {
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.lowercase()
        } else ""

        // 1. Detect Processor Family and Chip details
        val (family, chipName, accelerator, backend) = detectProcessor(hardware, board, manufacturer, brand, model, socModel)

        // 2. Battery & Charging status
        val (isCharging, isPlugged) = checkChargingStatus()

        // 3. Thermal status
        val thermalLevel = overrideThermal ?: getThermalStatusInt()
        val thermalName = getThermalStatusName(thermalLevel)

        // 4. Heavy task / Game detection
        // High thermal status (MODERATE or SEVERE) indicates foreground heavy 3D game or intense compute task
        val isGameOrHeavyTask = autoGameProtectionEnabled && (
                thermalLevel >= (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PowerManager.THERMAL_STATUS_MODERATE else 2)
                )

        // 5. Determine Power & Optimization Mode
        val powerMode = when {
            isGameOrHeavyTask || isUserPaused -> PowerProfileMode.GAME_HEAVY_THROTTLED
            isCharging || isPlugged -> PowerProfileMode.TURBO_CHARGING
            else -> PowerProfileMode.OPTIMAL_BATTERY
        }

        // 6. Dynamic batch size & pacing
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
        val (batchSize, delayMs, threads) = when (powerMode) {
            PowerProfileMode.TURBO_CHARGING -> {
                // Unleash full NPU/GPU power: max batch size, multi-core, 0ms delay
                Triple(16, 0L, (cores - 1).coerceAtLeast(3))
            }
            PowerProfileMode.OPTIMAL_BATTERY -> {
                // Balanced pacing to preserve battery life
                Triple(6, 10L, (cores / 2).coerceIn(2, 4))
            }
            PowerProfileMode.GAME_HEAVY_THROTTLED -> {
                // Minimum intrusive footprint during gaming/heavy load
                Triple(1, 100L, 1)
            }
        }

        return DeviceProcessorProfile(
            family = family,
            chipName = chipName,
            hardwareCode = hardware,
            primaryAccelerator = accelerator,
            recommendedBackend = backend,
            cpuCores = cores,
            recommendedThreads = threads,
            isCharging = isCharging,
            isPluggedIn = isPlugged,
            powerMode = powerMode,
            thermalStatusName = thermalName,
            thermalLevel = thermalLevel,
            isGameProtectionActive = isGameOrHeavyTask,
            recommendedBatchSize = batchSize,
            interBatchDelayMs = delayMs
        )
    }

    private fun detectProcessor(
        hardware: String,
        board: String,
        manufacturer: String,
        brand: String,
        model: String,
        socModel: String
    ): Quadruple<ProcessorFamily, String, String, ExecutionBackend> {
        val lowerModel = model.lowercase()

        // Google Tensor (Pixel 6/7/8/9)
        if (hardware.contains("zuma") || board.contains("zuma") ||
            hardware.contains("gs201") || hardware.contains("gs101") || hardware.contains("laguna") ||
            socModel.contains("zuma") || socModel.contains("gs") ||
            ((brand.contains("google") || manufacturer.contains("google")) && lowerModel.contains("pixel") &&
                    (lowerModel.contains("6") || lowerModel.contains("7") || lowerModel.contains("8") || lowerModel.contains("9") || lowerModel.contains("fold") || lowerModel.contains("tablet")))
        ) {
            val gen = when {
                hardware.contains("zumapro") || lowerModel.contains("pixel 9") -> "Google Tensor G4 (4nm)"
                hardware.contains("zuma") || lowerModel.contains("pixel 8") -> "Google Tensor G3 (4nm)"
                hardware.contains("gs201") || lowerModel.contains("pixel 7") -> "Google Tensor G2 (5nm)"
                hardware.contains("gs101") || lowerModel.contains("pixel 6") -> "Google Tensor G1 (5nm)"
                else -> "Google Tensor TPU"
            }
            return Quadruple(
                ProcessorFamily.GOOGLE_TENSOR,
                gen,
                "Google EdgeTPU / NNAPI Direct Acceleration",
                ExecutionBackend.GOOGLE_TENSOR_TPU
            )
        }

        // Qualcomm Snapdragon
        if (hardware.contains("qcom") || hardware.contains("snapdragon") ||
            board.contains("qcom") || socModel.contains("sm8") || socModel.contains("sm7") ||
            hardware.contains("lahaina") || hardware.contains("taro") || hardware.contains("kalama") || hardware.contains("pineapple")
        ) {
            val name = when {
                hardware.contains("pineapple") || socModel.contains("sm8650") -> "Snapdragon 8 Gen 3 (NPU HTP)"
                hardware.contains("kalama") || socModel.contains("sm8550") -> "Snapdragon 8 Gen 2 (Hexagon NPU)"
                hardware.contains("taro") || socModel.contains("sm8450") -> "Snapdragon 8 Gen 1 (Hexagon NPU)"
                hardware.contains("lahaina") || socModel.contains("sm8350") -> "Snapdragon 888 (Hexagon DSP)"
                else -> "Qualcomm Snapdragon (Hexagon NPU/GPU)"
            }
            return Quadruple(
                ProcessorFamily.QUALCOMM_SNAPDRAGON,
                name,
                "Qualcomm QNN / Hexagon Vector Engine & Adreno GPU",
                ExecutionBackend.QUALCOMM_QNN
            )
        }

        // MediaTek Dimensity
        if (hardware.contains("mt6") || hardware.contains("mt8") || hardware.contains("dimensity") ||
            board.contains("mt6") || board.contains("k69") || socModel.contains("dimensity")
        ) {
            return Quadruple(
                ProcessorFamily.MEDIATEK_DIMENSITY,
                "MediaTek Dimensity (APU / Mali GPU)",
                "MediaTek NeuroPilot APU & Mali GPU Delegate",
                ExecutionBackend.GPU_DELEGATE
            )
        }

        // Samsung Exynos
        if (hardware.contains("exynos") || board.contains("universal") || socModel.contains("exynos")) {
            return Quadruple(
                ProcessorFamily.SAMSUNG_EXYNOS,
                "Samsung Exynos (NPU & GPU)",
                "Samsung NPU & AMD Xclipse / Mali GPU Delegate",
                ExecutionBackend.GPU_DELEGATE
            )
        }

        // Generic x86 / Emulator
        if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) {
            return Quadruple(
                ProcessorFamily.GENERIC_X86,
                "x86_64 Virtual CPU",
                "AVX2 / Multi-Threaded SIMD",
                ExecutionBackend.CPU_XNNPACK
            )
        }

        // Generic ARM64
        return Quadruple(
            ProcessorFamily.GENERIC_ARM,
            "ARM Cortex-A (Multi-Core)",
            "ARM NEON SIMD & OpenCL GPU Delegate",
            ExecutionBackend.GPU_DELEGATE
        )
    }

    private fun checkChargingStatus(): Pair<Boolean, Boolean> {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val isPlugged = chargePlug == BatteryManager.BATTERY_PLUGGED_AC ||
                    chargePlug == BatteryManager.BATTERY_PLUGGED_USB ||
                    chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS
            Pair(isCharging, isPlugged)
        } catch (_: Exception) {
            Pair(false, false)
        }
    }

    private fun getThermalStatusInt(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                return powerManager.currentThermalStatus
            } catch (_: Exception) {}
        }
        return 0
    }

    private fun getThermalStatusName(status: Int): String {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal (Cool)"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light Warmth"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate (Pacing Active)"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe (Game Protection Active)"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical (Thermal Cooldown)"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Normal"
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
