package com.example

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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterTest {

    @Test
    fun testSimulationUpdate_generatesNewerVersion() {
        val context = RuntimeEnvironment.getApplication()
        val checker = AppUpdateChecker(context)

        val currentCode = 1
        val currentName = "1.0"

        val result = checker.checkSimulationUpdate(currentCode, currentName)
        assertTrue(result is UpdateCheckResult.UpdateAvailable)

        val update = (result as UpdateCheckResult.UpdateAvailable).info
        assertTrue(update.versionCode > currentCode)
        assertEquals("1.1.0", update.versionName)
        assertTrue(update.releaseNotes.contains("In-App APK Self-Updater"))
        assertTrue(update.formattedFileSize.isNotEmpty())
    }

    @Test
    fun testUpdateConfig_defaultSettings() {
        val config = UpdateConfig()
        assertEquals(UpdateSourceType.GITHUB_RELEASES, config.sourceType)
        assertTrue(config.autoCheckEnabled)
        assertTrue(config.githubRepo.isNotEmpty())
        assertTrue(config.customManifestUrl.isNotEmpty())
    }

    @Test
    fun testAppUpdateInfo_formattedFileSize() {
        val infoSmall = AppUpdateInfo(
            versionCode = 2,
            versionName = "1.1",
            releaseNotes = "Test",
            downloadUrl = "https://example.com/app.apk",
            fileSizeBytes = 512 * 1024 // 512 KB
        )
        assertEquals("512 KB", infoSmall.formattedFileSize)

        val infoMb = AppUpdateInfo(
            versionCode = 3,
            versionName = "1.2",
            releaseNotes = "Test",
            downloadUrl = "https://example.com/app.apk",
            fileSizeBytes = 18 * 1024 * 1024 // 18 MB
        )
        assertEquals("18.0 MB", infoMb.formattedFileSize)
    }
}
