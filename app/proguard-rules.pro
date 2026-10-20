# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# TensorFlow Lite and LiteRT rules
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class com.google.android.gms.tflite.** { *; }
-dontwarn com.google.android.gms.tflite.**
-keep class com.qualcomm.qti.** { *; }
-dontwarn com.qualcomm.qti.**

# Room and SQLite rules
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# PDFBox-Android (text extraction). Optional JPEG2000 / crypto providers are not bundled.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# --- Release shrinking (R8) ---
# Shrink only: keep class and member names so reflection, JNI callbacks, resource lookups relative to class
# packages (PDFBox) and stack traces from users keep working. Most of the size saving comes from removing
# unused library code, not from renaming.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# The app's own code is small; keep all of it (Room entities, WorkManager workers, widget, updater models,
# classes used by reflection).
-keep class com.example.** { *; }

# ML Kit text recognition: components are registered by name and the native pipeline calls back into Java.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# Kotlin metadata (needed by anything reading Kotlin classes reflectively).
-keep class kotlin.Metadata { *; }
