package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.updater.engine.AppUpdateChecker
import com.example.updater.model.AppUpdateInfo
import com.example.updater.model.UpdateCheckResult
import com.example.updater.model.UpdateConfig
import com.example.updater.model.UpdateSourceType
import com.example.updater.repository.UpdatePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Android Service that fetches a version manifest from a custom URL to check for
 * new APK releases and notifies the user with an interactive system update notification.
 */
class AppUpdateService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private lateinit var checker: AppUpdateChecker
    private lateinit var preferences: UpdatePreferences

    private val _serviceCheckResult = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Idle)
    val serviceCheckResult: StateFlow<UpdateCheckResult> = _serviceCheckResult.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): AppUpdateService = this@AppUpdateService
    }

    override fun onCreate() {
        super.onCreate()
        checker = AppUpdateChecker(this, okHttpClient)
        preferences = UpdatePreferences(this)
        createNotificationChannel()
        Log.i(TAG, "AppUpdateService initialized.")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CHECK_UPDATE
        when (action) {
            ACTION_CHECK_UPDATE -> {
                val manifestUrl = intent?.getStringExtra(EXTRA_MANIFEST_URL)
                    ?: preferences.config.value.customManifestUrl
                val isForce = intent?.getBooleanExtra(EXTRA_FORCE_CHECK, false) ?: false
                performUpdateCheck(manifestUrl, isForce)
            }
            ACTION_DISMISS_UPDATE -> {
                val dismissedVersion = intent?.getIntExtra(EXTRA_VERSION_CODE, 0) ?: 0
                if (dismissedVersion > 0) {
                    preferences.dismissVersion(dismissedVersion)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIFICATION_UPDATE_ID)
                }
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Checks for updates against the provided version manifest URL and notifies the user if a new APK is available.
     */
    fun performUpdateCheck(manifestUrl: String, forceCheck: Boolean = false) {
        serviceScope.launch {
            _serviceCheckResult.value = UpdateCheckResult.Checking
            try {
                val config = UpdateConfig(
                    sourceType = UpdateSourceType.JSON_MANIFEST,
                    customManifestUrl = manifestUrl,
                    autoCheckEnabled = true
                )

                val result = checker.checkForUpdates(config, forceCheck = forceCheck)
                preferences.recordLastCheckTime()
                _serviceCheckResult.value = result

                when (result) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        val update = result.info
                        val dismissed = preferences.config.value.dismissedVersionCode
                        if (forceCheck || update.versionCode > dismissed) {
                            showUpdateNotification(update)
                        }
                    }
                    is UpdateCheckResult.UpToDate -> {
                        Log.i(TAG, "Application is up to date (v${result.currentVersionName}, code ${result.currentVersionCode}).")
                    }
                    is UpdateCheckResult.Error -> {
                        Log.w(TAG, "Update check failed: ${result.message}")
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "AppUpdateService encountered error checking updates: ${e.message}", e)
                _serviceCheckResult.value = UpdateCheckResult.Error(
                    message = e.localizedMessage ?: "Failed to check update manifest",
                    throwable = e
                )
            }
        }
    }

    private fun showUpdateNotification(info: AppUpdateInfo) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SHOW_UPDATE, true)
            putExtra(EXTRA_UPDATE_URL, info.downloadUrl)
            putExtra(EXTRA_VERSION_NAME, info.versionName)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_UPDATE,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_UPDATE_ID)
            .setSmallIcon(R.drawable.ic_localdoc_symbol)
            .setContentTitle("New Release Available: v${info.versionName}")
            .setContentText("${info.releaseTitle} is ready to download (${info.formattedFileSize}). Tap to update.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "New version v${info.versionName} (${info.formattedFileSize}):\n${info.releaseNotes}\n\nTap to download APK and update."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_UPDATE_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_UPDATE_ID,
                "Software Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for new app versions, APK updates, and release manifests"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "AppUpdateService stopped.")
    }

    companion object {
        private const val TAG = "AppUpdateService"

        const val CHANNEL_UPDATE_ID = "app_update_channel"
        const val NOTIFICATION_UPDATE_ID = 2002
        const val REQUEST_CODE_UPDATE = 4004

        const val ACTION_CHECK_UPDATE = "com.example.service.action.CHECK_UPDATE"
        const val ACTION_DISMISS_UPDATE = "com.example.service.action.DISMISS_UPDATE"

        const val EXTRA_MANIFEST_URL = "extra_manifest_url"
        const val EXTRA_FORCE_CHECK = "extra_force_check"
        const val EXTRA_VERSION_CODE = "extra_version_code"
        const val EXTRA_SHOW_UPDATE = "extra_show_in_app_update_sheet"
        const val EXTRA_UPDATE_URL = "extra_update_download_url"
        const val EXTRA_VERSION_NAME = "extra_update_version_name"

        /**
         * Convenience method to start the update check service from any Activity, BroadcastReceiver or Worker.
         */
        fun startCheck(context: Context, customManifestUrl: String? = null, forceCheck: Boolean = false) {
            val intent = Intent(context, AppUpdateService::class.java).apply {
                action = ACTION_CHECK_UPDATE
                if (!customManifestUrl.isNullOrBlank()) {
                    putExtra(EXTRA_MANIFEST_URL, customManifestUrl)
                }
                putExtra(EXTRA_FORCE_CHECK, forceCheck)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start AppUpdateService: ${e.message}")
            }
        }
    }
}
