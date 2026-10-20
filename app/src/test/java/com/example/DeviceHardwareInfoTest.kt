package com.example

import com.example.engine.DeviceHardwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceHardwareInfoTest {
    @Test
    fun mapsPixelBoardCodesToTensorGenerations() {
        assertEquals("Google Tensor G1", DeviceHardwareInfo.tensorGeneration("gs101"))
        assertEquals("Google Tensor G3", DeviceHardwareInfo.tensorGeneration("ZUMA"))
        assertEquals("Google Tensor G4", DeviceHardwareInfo.tensorGeneration("zumapro"))
    }

    @Test
    fun otherChipsAreNotTensor() {
        assertNull(DeviceHardwareInfo.tensorGeneration("qcom"))
        assertNull(DeviceHardwareInfo.tensorGeneration(""))
    }
}
