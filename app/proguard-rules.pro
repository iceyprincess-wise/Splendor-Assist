-keep class com.google.mlkit.** { *; }
-keep class com.assistant.EngineData { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn com.google.mlkit.**
