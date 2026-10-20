package com.example

import com.example.updater.engine.ApkAssetChooser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkAssetChooserTest {

    private val release = listOf(
        "LocalDocFinder-v1.2.1-universal.apk",
        "LocalDocFinder-v1.2.1-universal.apk.sha256",
        "LocalDocFinder-v1.2.1-armeabi-v7a.apk",
        "LocalDocFinder-v1.2.1-arm64-v8a.apk",
        "LocalDocFinder-v1.2.1-x86_64.apk",
        "SHA256SUMS.txt"
    )

    @Test
    fun picksTheApkForThePhonesPreferredProcessor() {
        assertEquals(3, ApkAssetChooser.choose(release, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(2, ApkAssetChooser.choose(release, listOf("armeabi-v7a", "armeabi")))
        assertEquals(4, ApkAssetChooser.choose(release, listOf("x86_64", "arm64-v8a")))
    }

    @Test
    fun eachBuildOnlyUpdatesToItsOwnKindOfApk() {
        val both = listOf(
            "LocalDocFinder-v1.3.0-universal.apk",
            "LocalDocFinder-v1.3.0-arm64-v8a.apk",
            "LocalDocFinder-v1.3.0-dev-universal.apk",
            "LocalDocFinder-v1.3.0-dev-arm64-v8a.apk",
            "SHA256SUMS.txt"
        )
        assertEquals(1, ApkAssetChooser.choose(both, listOf("arm64-v8a")))
        assertEquals(3, ApkAssetChooser.choose(both, listOf("arm64-v8a"), dev = true))
        assertEquals(2, ApkAssetChooser.choose(both, listOf("riscv64"), dev = true))
        assertNull(ApkAssetChooser.choose(release, listOf("arm64-v8a"), dev = true))
    }

    @Test
    fun fallsBackToUniversalThenToOlderSingleApkReleases() {
        assertEquals(0, ApkAssetChooser.choose(release, listOf("riscv64")))
        assertEquals(0, ApkAssetChooser.choose(listOf("LocalDocFinder-v1.2.0.apk"), listOf("arm64-v8a")))
        assertNull(ApkAssetChooser.choose(listOf("LocalDocFinder-v1.2.1-x86_64.apk"), listOf("arm64-v8a")))
        assertNull(ApkAssetChooser.choose(listOf("notes.md"), listOf("arm64-v8a")))
    }
}
