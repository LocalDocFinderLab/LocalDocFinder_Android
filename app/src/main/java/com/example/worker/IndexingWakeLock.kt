package com.example.worker

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Utility to manage partial wake locks during intensive background indexing.
 * Prevents CPU sleep while the screen is off or the device enters Doze mode,
 * ensuring continuous indexing without sudden process suspension or death.
 */
object IndexingWakeLock {

    private const val TAG = "IndexingWakeLock"

    fun acquire(context: Context, tag: String, timeoutMs: Long = 45 * 60 * 1000L): PowerManager.WakeLock? {
        return try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DocuVector:$tag")?.apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
                Log.d(TAG, "Acquired partial WakeLock for $tag (timeout: ${timeoutMs / 1000}s)")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to acquire WakeLock for $tag: ${e.message}")
            null
        }
    }

    fun release(wakeLock: PowerManager.WakeLock?, tag: String = "WakeLock") {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
                Log.d(TAG, "Released partial WakeLock for $tag")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing WakeLock for $tag: ${e.message}")
        }
    }
}
