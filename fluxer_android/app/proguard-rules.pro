# Fluxer Android — WebView wrapper, minimal proguard needed
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Keep WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AndroidX
-dontwarn androidx.**
-keep class androidx.** { *; }
