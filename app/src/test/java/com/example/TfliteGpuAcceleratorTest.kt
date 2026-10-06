package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.TfliteGpuAccelerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/** On the JVM there is no GPU/native TFLite runtime: every path must degrade gracefully, never throw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TfliteGpuAcceleratorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun gpuReportedUnsupportedWithoutThrowing() {
        assertFalse(TfliteGpuAccelerator.isGpuSupported())
    }

    @Test
    fun gpuDelegateIsNullWhenUnsupported() {
        assertNull(TfliteGpuAccelerator.createGpuDelegate(context, "test_token"))
    }

    @Test
    fun invalidModelYieldsNullInterpreterInsteadOfCrash() {
        val garbage = ByteBuffer.allocateDirect(16)
        assertNull(TfliteGpuAccelerator.createInterpreter(context, garbage, "test_token", cpuThreads = 2))
    }
}
