-keep class com.google.mlkit.** { *; }
-keep class com.assistant.EngineData { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn com.google.mlkit.**
-keep class com.assistant.overlay.interceptor.SmartAssistAccessibilityEngine { *; }
-keep class com.assistant.adapter.** extends android.app.Service { *; }
-keep class com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
