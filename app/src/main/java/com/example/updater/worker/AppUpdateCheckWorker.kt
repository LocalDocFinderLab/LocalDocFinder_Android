package com.example.updater.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.updater.engine.AppUpdateChecker
import com.example.updater.model.UpdateCheckResult
import com.example.updater.repository.UpdatePreferences
import java.util.concurrent.TimeUnit

class AppUpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val preferences = UpdatePreferences(context)
    private val checker = AppUpdateChecker(context)

    override suspend fun doWork(): Result {
        val config = preferences.config.value
        if (!config.autoCheckEnabled) {
            Log.d(TAG, "Auto update check is disabled in settings")
            return Result.success()
        }

        return try {
            val result = checker.checkForUpdates(config)
            preferences.recordLastCheckTime()

            if (result is UpdateCheckResult.UpdateAvailable) {
                val info = result.info
                if (info.versionCode > config.dismissedVersionCode) {
                    showUpdateNotification(info.versionName, info.releaseTitle, info.formattedFileSize)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Periodic update check failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun showUpdateNotification(versionName: String, title: String, fileSize: String) {
        ensureNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SHOW_UPDATE, true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_UPDATE_NOTIFICATION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATE_ID)
            .setSmallIcon(R.drawable.ic_localdoc_symbol)
            .setContentTitle("New Version Available: v$versionName")
            .setContentText("LocalDoc Finder $versionName is ready to download ($fileSize). Tap to update.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "A new update ($title) is available with performance improvements and offline vector updates. Tap to download and install."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_UPDATE_ID, notification)
    }

    companion object {
        private const val TAG = "AppUpdateCheckWorker"
        const val CHANNEL_UPDATE_ID = "app_update_notifications"
        const val NOTIFICATION_UPDATE_ID = 2002
        const val REQUEST_CODE_UPDATE_NOTIFICATION = 3003
        const val EXTRA_SHOW_UPDATE = "extra_show_in_app_update_sheet"
        const val WORK_NAME = "PeriodicAppUpdateCheck"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_UPDATE_ID,
                    "App Update Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifies when new APK updates or releases are available"
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun schedulePeriodicCheck(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(
                    24, TimeUnit.HOURS,
                    6, TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule periodic update check: ${e.message}")
            }
        }
    }
}
