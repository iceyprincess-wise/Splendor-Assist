-keep class com.google.mlkit.** { *; }
-keep class com.assistant.EngineData { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn com.google.mlkit.**
# Stale keeps removed (batch 5): the two SmartAssistAccessibilityEngine
# keeps below named paths that do not exist
# (com.assistant.overlay.interceptor.* and
# com.assistant.adapter.smartassist.*). The live class is
# com.assistant.SmartAssistAccessibilityEngine and it is already kept by
# core/consumer-rules.pro ("* extends AccessibilityService") plus the
# manifest service declaration — no app keep is needed.
-keep class com.assistant.adapter.** extends android.app.Service { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
