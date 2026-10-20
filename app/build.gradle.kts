plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.vectorsearch.dvmxqe"
    minSdk = 28
    targetSdk = 36
    // versionCode follows the in-app updater's tag scheme (major*10000 + minor*100 + patch), so v1.1.0 -> 10100.
    versionCode = 10301
    versionName = "1.3.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  // Two builds of the same code: "prod" is the app people use; "dev" is for testing on devices. It adds a
  // Developer console (logs, what the app sends over the network, index and permission state) and the
  // sample-data tools. Different application ids, so both can be installed side by side.
  flavorDimensions += "channel"
  productFlavors {
    create("prod") {
      dimension = "channel"
      buildConfigField("boolean", "DEV_MODE", "false")
      resValue("string", "app_name", "LocalDoc Finder")
      resValue("string", "shortcut_package", "com.aistudio.vectorsearch.dvmxqe")
    }
    create("dev") {
      dimension = "channel"
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"
      buildConfigField("boolean", "DEV_MODE", "true")
      resValue("string", "app_name", "LocalDoc Dev")
      resValue("string", "shortcut_package", "com.aistudio.vectorsearch.dvmxqe.dev")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      // R8 removes unused library code (most of the size of the old builds). Names are kept
      // (-dontobfuscate in proguard-rules.pro) so reflection and stack traces keep working.
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Signed with your key when KEYSTORE_PATH / STORE_PASSWORD are set (CI secrets), otherwise with the debug
      // keystore so a release build still installs.
      val hasReleaseKey = !System.getenv("KEYSTORE_PATH").isNullOrBlank() && !System.getenv("STORE_PASSWORD").isNullOrBlank()
      signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debugConfig")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
    resValues = true // per-flavor app name and shortcut package (see productFlavors)
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  // One APK per processor type, so a phone downloads only the native code it can run (the OCR engine alone
  // is ~11 MB per type), plus a universal APK that runs everywhere. 32-bit x86 (old emulators only) is left out.
  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a", "x86_64")
      isUniversalApk = true
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Export Room schema JSON (used for migration verification and version history).
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.exifinterface)
  // Apache PDFBox port for Android (Apache Tika is not usable on Android: it needs java.awt/javax.xml.stream)
  implementation(libs.pdfbox.android)
  // On-device OCR (bundled Latin model, no network): reads text in photos, screenshots and scanned PDFs.
  implementation(libs.mlkit.text.recognition)
  implementation(libs.tensorflow.lite)
  implementation(libs.tensorflow.lite.gpu)
  implementation(libs.tensorflow.lite.gpu.api)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.work.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
