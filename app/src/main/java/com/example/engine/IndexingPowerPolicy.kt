package com.example.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IndexingPowerPolicy
 *
 * Decides, live, how hard the indexer is allowed to push the device:
 *
 * - TURBO: charging AND the user is not using the phone (screen off / Doze) -> full CPU, GPU and NPU
 *   throughput: biggest embedding batches, zero pacing delay, all cores, several files in parallel.
 * - STANDARD / LIGHT: charging while in use, or idle on battery, or in use on battery -> progressively
 *   gentler so the foreground app stays smooth and the battery lasts.
 * - FULL_SPEED: the user switched on "Full speed" (e.g. for the first big indexing run): same throughput as
 *   TURBO regardless of charging, screen use or battery saver. Only pausing and a severely hot device still win.
 * - THERMAL_GUARD / PAUSED: device is hot (games, heavy tasks) or the user paused indexing.
 *
 * The UI observes [speed] and shows a "slowed" bar whenever [Speed.isSlowed] is true.
 */
object IndexingPowerPolicy {

    private const val TAG = "IndexingPowerPolicy"

    // PowerManager.THERMAL_STATUS_* values (API 29+). Inlined so the pure policy is testable on any API level.
    private const val THERMAL_MODERATE = 3
    private const val THERMAL_SEVERE = 4

    /** Upper bound on files embedded concurrently; the live value is [Speed.parallelFiles]. */
    const val MAX_PARALLEL_FILES = 3

    enum class Mode(val label: String) {
        TURBO("Turbo"),
        FULL_SPEED("Full speed"),
        STANDARD("Standard"),
        LIGHT("Light"),
        THERMAL_GUARD("Cooling down"),
        PAUSED("Paused")
    }

    enum class Reason {
        NONE,
        USER_ACTIVE,
        ON_BATTERY,
        ON_BATTERY_AND_ACTIVE,
        POWER_SAVE,
        THERMAL,
        USER_PAUSED,
        USER_FORCED
    }

    data class Inputs(
        val isCharging: Boolean = false,
        val isUserActive: Boolean = true,
        val isPowerSave: Boolean = false,
        val thermalStatus: Int = 0,
        val isUserPaused: Boolean = false,
        /** Manual "Full speed" override chosen by the user. */
        val forceFullSpeed: Boolean = false,
        val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    )

    data class Speed(
        val mode: Mode,
        val reason: Reason,
        /** Relative throughput versus full turbo, 0..100. */
        val speedPercent: Int,
        val batchSize: Int,
        val interBatchDelayMs: Long,
        val cpuThreads: Int,
        val parallelFiles: Int
    ) {
        val isTurbo: Boolean get() = mode == Mode.TURBO
        val isFullSpeed: Boolean get() = mode == Mode.FULL_SPEED
        val isSlowed: Boolean get() = speedPercent < 100

        /** Short human explanation of why indexing runs at this speed. */
        fun explanation(): String = when (reason) {
            Reason.NONE -> "Charging & idle: using full CPU, GPU and NPU power"
            Reason.USER_ACTIVE -> "You're using the phone: leaving power for the foreground app"
            Reason.ON_BATTERY -> "Running on battery: plug in and lock the screen for full speed"
            Reason.ON_BATTERY_AND_ACTIVE -> "On battery while in use: plug in and lock the screen for full speed"
            Reason.POWER_SAVE -> "Battery saver is on: indexing is throttled"
            Reason.THERMAL -> "Device is warm: slowing down to cool off and protect gaming performance"
            Reason.USER_PAUSED -> "Paused by you"
            Reason.USER_FORCED -> "Full speed on your request: all cores in use. The phone may warm up and the battery drain faster"
        }

        /** One-line summary for notifications. */
        fun summary(): String = when {
            mode == Mode.PAUSED -> "Paused"
            isFullSpeed -> "Full speed"
            isTurbo -> "Turbo"
            else -> "Slowed to $speedPercent%"
        }
    }

    /** Pure decision function: no Android state is read here. */
    fun decide(inputs: Inputs): Speed {
        val cores = inputs.cores.coerceAtLeast(2)
        return when {
            inputs.isUserPaused ->
                Speed(Mode.PAUSED, Reason.USER_PAUSED, 0, 1, 100L, 1, 1)

            inputs.thermalStatus >= THERMAL_SEVERE ->
                Speed(Mode.THERMAL_GUARD, Reason.THERMAL, 10, 1, 100L, 1, 1)

            // User override: ignores battery, screen use, battery saver and moderate warmth (severe heat above
            // still wins to protect the device).
            inputs.forceFullSpeed ->
                Speed(
                    Mode.FULL_SPEED, Reason.USER_FORCED, 100,
                    batchSize = 16,
                    interBatchDelayMs = 0L,
                    cpuThreads = (cores - 1).coerceAtLeast(3),
                    parallelFiles = (cores / 3).coerceIn(1, MAX_PARALLEL_FILES)
                )

            inputs.thermalStatus >= THERMAL_MODERATE ->
                Speed(Mode.THERMAL_GUARD, Reason.THERMAL, 25, 4, 40L, 2, 1)

            inputs.isPowerSave ->
                Speed(Mode.LIGHT, Reason.POWER_SAVE, 20, 2, 60L, 2, 1)

            inputs.isCharging && !inputs.isUserActive ->
                Speed(
                    Mode.TURBO, Reason.NONE, 100,
                    batchSize = 16,
                    interBatchDelayMs = 0L,
                    cpuThreads = (cores - 1).coerceAtLeast(3),
                    parallelFiles = (cores / 3).coerceIn(1, MAX_PARALLEL_FILES)
                )

            inputs.isCharging ->
                Speed(Mode.STANDARD, Reason.USER_ACTIVE, 55, 8, 10L, (cores / 2).coerceIn(2, 4), 1)

            !inputs.isUserActive ->
                Speed(Mode.STANDARD, Reason.ON_BATTERY, 45, 6, 15L, (cores / 2).coerceIn(2, 4), 1)

            else ->
                Speed(Mode.LIGHT, Reason.ON_BATTERY_AND_ACTIVE, 30, 4, 40L, 2, 1)
        }
    }

    private var inputs = Inputs()
    private val _speed = MutableStateFlow(decide(inputs))
    val speed: StateFlow<Speed> = _speed.asStateFlow()

    fun current(): Speed = _speed.value

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    private var receiver: BroadcastReceiver? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    @Synchronized
    private fun update(transform: (Inputs) -> Inputs) {
        inputs = transform(inputs)
        _speed.value = decide(inputs)
    }

    fun setUserPaused(paused: Boolean) = update { it.copy(isUserPaused = paused) }

    private val _forceFullSpeed = MutableStateFlow(false)

    /** Whether the user has switched on manual Full speed. Persisted, so it survives restarts. */
    val forceFullSpeed: StateFlow<Boolean> = _forceFullSpeed.asStateFlow()

    private const val PREFS_NAME = "indexing_power_prefs"
    private const val KEY_FULL_SPEED = "force_full_speed"

    /** Turns the manual Full speed override on or off and remembers the choice. */
    fun setForceFullSpeed(context: Context, enabled: Boolean) {
        try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FULL_SPEED, enabled).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist full-speed choice: ${e.message}")
        }
        _forceFullSpeed.value = enabled
        update { it.copy(forceFullSpeed = enabled) }
    }

    private fun loadForceFullSpeed(context: Context) {
        val saved = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_FULL_SPEED, false)
        } catch (_: Exception) {
            false
        }
        _forceFullSpeed.value = saved
        update { it.copy(forceFullSpeed = saved) }
    }

    /** Begin tracking charging, screen/idle, battery saver and thermal state. Safe to call repeatedly. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext ?: context
        appContext = app
        started = true
        loadForceFullSpeed(app)

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    syncFromSystem()
                }
            }
            ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (e: Exception) {
            Log.w(TAG, "Could not register power receiver: ${e.message}")
        }

        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            try {
                val l = PowerManager.OnThermalStatusChangedListener { status ->
                    update { it.copy(thermalStatus = status) }
                }
                pm.addThermalStatusListener(app.mainExecutor, l)
                thermalListener = l
            } catch (e: Exception) {
                Log.w(TAG, "Could not register thermal listener: ${e.message}")
            }
        }

        syncFromSystem()
    }

    /** Re-read charging / interactive / battery-saver / thermal state from the OS. */
    fun syncFromSystem() {
        val app = appContext ?: return
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val charging = try {
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plug = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plug != 0
        } catch (_: Exception) {
            false
        }

        val userActive = try {
            pm != null && pm.isInteractive && !pm.isDeviceIdleMode
        } catch (_: Exception) {
            true
        }

        val powerSave = try { pm?.isPowerSaveMode == true } catch (_: Exception) { false }

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            try { pm.currentThermalStatus } catch (_: Exception) { 0 }
        } else {
            0
        }

        update {
            it.copy(
                isCharging = charging,
                isUserActive = userActive,
                isPowerSave = powerSave,
                thermalStatus = thermal
            )
        }
    }

    @Synchronized
    fun stop() {
        val app = appContext
        if (app != null) {
            receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                thermalListener?.let { try { pm?.removeThermalStatusListener(it) } catch (_: Exception) {} }
            }
        }
        receiver = null
        thermalListener = null
        started = false
    }
}
