package com.example.updater.engine

/**
 * Picks which APK of a release to download. Releases carry one APK per processor type
 * (`LocalDocFinder-v1.2.1-arm64-v8a.apk`, ...) plus a larger universal one that runs everywhere. The dev build's
 * APKs sit next to them with `-dev-` in the name (`LocalDocFinder-v1.3.0-dev-arm64-v8a.apk`); each build only ever
 * updates to its own kind, because the two are different apps.
 */
object ApkAssetChooser {

    /**
     * Index in [assetNames] of the APK for this device: the first of [supportedAbis] (the device's
     * `Build.SUPPORTED_ABIS`, most preferred first) that has its own APK, else the universal APK, else the
     * first APK of any kind. Null when the release has no APK.
     */
    fun choose(assetNames: List<String>, supportedAbis: List<String>, dev: Boolean = false): Int? {
        val apks = assetNames.withIndex().filter {
            it.value.endsWith(".apk", ignoreCase = true) && isDevApk(it.value) == dev
        }
        if (apks.isEmpty()) return null
        for (abi in supportedAbis) {
            apks.firstOrNull { it.value.endsWith("-$abi.apk", ignoreCase = true) }?.let { return it.index }
        }
        apks.firstOrNull { it.value.contains("universal", ignoreCase = true) }?.let { return it.index }
        val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        // Never hand a device an APK built for another processor type.
        return apks.firstOrNull { a -> knownAbis.none { a.value.endsWith("-$it.apk", ignoreCase = true) } }?.index
    }

    private fun isDevApk(name: String): Boolean = name.contains("-dev-", ignoreCase = true)
}
