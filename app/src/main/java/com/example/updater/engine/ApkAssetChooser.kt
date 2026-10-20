package com.example.updater.engine

/**
 * Picks which APK of a release to download. Releases carry one APK per processor type
 * (`LocalDocFinder-v1.2.1-arm64-v8a.apk`, ...) plus a larger universal one that runs everywhere.
 */
object ApkAssetChooser {

    /**
     * Index in [assetNames] of the APK for this device: the first of [supportedAbis] (the device's
     * `Build.SUPPORTED_ABIS`, most preferred first) that has its own APK, else the universal APK, else the
     * first APK of any kind. Null when the release has no APK.
     */
    fun choose(assetNames: List<String>, supportedAbis: List<String>): Int? {
        val apks = assetNames.withIndex().filter { it.value.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        for (abi in supportedAbis) {
            apks.firstOrNull { it.value.endsWith("-$abi.apk", ignoreCase = true) }?.let { return it.index }
        }
        apks.firstOrNull { it.value.contains("universal", ignoreCase = true) }?.let { return it.index }
        val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        // Never hand a device an APK built for another processor type.
        return apks.firstOrNull { a -> knownAbis.none { a.value.endsWith("-$it.apk", ignoreCase = true) } }?.index
    }
}
