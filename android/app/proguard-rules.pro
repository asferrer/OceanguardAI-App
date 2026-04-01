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

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# llama.cpp JNI bridge — keep JNI-called Kotlin classes and callback interface
-keep class com.oceanguard.ai.inference.LlamaCppBridge { *; }
-keep interface com.oceanguard.ai.inference.TokenStreamCallback { *; }
-keepclassmembers interface com.oceanguard.ai.inference.TokenStreamCallback {
    public void onToken(java.lang.String);
}

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep data classes (for JSON parsing)
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.oceanguard.ai.data.** { *; }

# Keep MapLibre native classes
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# TensorFlow Lite GPU — classes referenced but stripped by litert-api exclusion
-dontwarn org.tensorflow.lite.gpu.**

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
