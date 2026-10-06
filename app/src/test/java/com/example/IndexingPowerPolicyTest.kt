package com.example

import com.example.engine.IndexingPowerPolicy
import com.example.engine.IndexingPowerPolicy.Inputs
import com.example.engine.IndexingPowerPolicy.Mode
import com.example.engine.IndexingPowerPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingPowerPolicyTest {

    private fun idleCharging() = Inputs(isCharging = true, isUserActive = false, cores = 8)

    @Test
    fun chargingAndIdleIsFullTurbo() {
        val s = IndexingPowerPolicy.decide(idleCharging())
        assertEquals(Mode.TURBO, s.mode)
        assertEquals(100, s.speedPercent)
        assertFalse(s.isSlowed)
        assertEquals(0L, s.interBatchDelayMs)
        assertEquals(7, s.cpuThreads)
        assertTrue(s.batchSize >= 16)
        assertTrue(s.parallelFiles > 1)
    }

    @Test
    fun userPickingUpPhoneWhileChargingSlowsIndexing() {
        val s = IndexingPowerPolicy.decide(idleCharging().copy(isUserActive = true))
        assertEquals(Mode.STANDARD, s.mode)
        assertEquals(Reason.USER_ACTIVE, s.reason)
        assertTrue(s.isSlowed)
        assertEquals(1, s.parallelFiles)
    }

    @Test
    fun unpluggedIdleIsSlowedForBattery() {
        val s = IndexingPowerPolicy.decide(idleCharging().copy(isCharging = false))
        assertEquals(Reason.ON_BATTERY, s.reason)
        assertTrue(s.isSlowed)
    }

    @Test
    fun unpluggedAndInUseIsLightest() {
        val s = IndexingPowerPolicy.decide(Inputs(isCharging = false, isUserActive = true, cores = 8))
        assertEquals(Mode.LIGHT, s.mode)
        assertEquals(Reason.ON_BATTERY_AND_ACTIVE, s.reason)
        assertTrue(s.speedPercent < IndexingPowerPolicy.decide(idleCharging().copy(isUserActive = true)).speedPercent)
    }

    @Test
    fun thermalPressureOverridesTurbo() {
        val moderate = IndexingPowerPolicy.decide(idleCharging().copy(thermalStatus = 3))
        assertEquals(Mode.THERMAL_GUARD, moderate.mode)
        assertTrue(moderate.isSlowed)

        val severe = IndexingPowerPolicy.decide(idleCharging().copy(thermalStatus = 4))
        assertTrue(severe.speedPercent < moderate.speedPercent)
        assertEquals(1, severe.batchSize)
    }

    @Test
    fun batterySaverThrottlesEvenWhenCharging() {
        val s = IndexingPowerPolicy.decide(idleCharging().copy(isPowerSave = true))
        assertEquals(Reason.POWER_SAVE, s.reason)
        assertTrue(s.isSlowed)
    }

    @Test
    fun userPauseWinsOverEverything() {
        val s = IndexingPowerPolicy.decide(idleCharging().copy(isUserPaused = true))
        assertEquals(Mode.PAUSED, s.mode)
        assertEquals(0, s.speedPercent)
    }

    @Test
    fun turboNeverSpawnsMoreWorkersThanAllowed() {
        val s = IndexingPowerPolicy.decide(Inputs(isCharging = true, isUserActive = false, cores = 64))
        assertTrue(s.parallelFiles <= IndexingPowerPolicy.MAX_PARALLEL_FILES)
    }

    @Test
    fun liveStateReflectsUserPause() {
        IndexingPowerPolicy.setUserPaused(true)
        assertEquals(Mode.PAUSED, IndexingPowerPolicy.current().mode)
        IndexingPowerPolicy.setUserPaused(false)
        assertFalse(IndexingPowerPolicy.current().mode == Mode.PAUSED)
    }
}
