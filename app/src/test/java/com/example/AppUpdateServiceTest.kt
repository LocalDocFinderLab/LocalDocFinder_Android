package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.service.AppUpdateService
import com.example.updater.engine.AppUpdateChecker
import com.example.updater.model.AppUpdateInfo
import com.example.updater.model.UpdateCheckResult
import com.example.updater.model.UpdateConfig
import com.example.updater.model.UpdateSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateServiceTest {

    @Test
    fun testAppUpdateServiceConstantsAndIntent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val customManifestUrl = "https://custom-server.example.com/manifests/release-v2.0.json"

        val intent = Intent(context, AppUpdateService::class.java).apply {
            action = AppUpdateService.ACTION_CHECK_UPDATE
            putExtra(AppUpdateService.EXTRA_MANIFEST_URL, customManifestUrl)
            putExtra(AppUpdateService.EXTRA_FORCE_CHECK, true)
        }

        assertEquals(AppUpdateService.ACTION_CHECK_UPDATE, intent.action)
        assertEquals(customManifestUrl, intent.getStringExtra(AppUpdateService.EXTRA_MANIFEST_URL))
        assertTrue(intent.getBooleanExtra(AppUpdateService.EXTRA_FORCE_CHECK, false))
    }

    @Test
    fun testAppUpdateCheckerJsonManifestParsingLogic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val checker = AppUpdateChecker(context)

        // Verifies simulated manifest check returns newer available update
        val result = checker.checkSimulationUpdate(currentVersionCode = 1, currentVersionName = "1.0")
        assertTrue(result is UpdateCheckResult.UpdateAvailable)

        val info = (result as UpdateCheckResult.UpdateAvailable).info
        assertTrue(info.versionCode > 1)
        assertEquals("1.1.0", info.versionName)
        assertTrue(info.downloadUrl.isNotEmpty())
    }

    @Test
    fun testAppUpdateServiceLifecycle() {
        val controller = Robolectric.buildService(AppUpdateService::class.java)
        val service = controller.create().get()

        assertNotNull(service)
        val binder = service.onBind(Intent())
        assertNotNull(binder)

        controller.destroy()
    }
}
