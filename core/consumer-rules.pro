# ==========================================
# CORE MODULE CONSUMER RULES (AGGREGATED)
# ==========================================

# 1. Retain Core Engine Interfaces & Data Classes
-keep interface com.assistant.runtime.** { *; }
-keep class com.assistant.runtime.** { *; }
-keep class com.assistant.execution.** { *; }
-keep class com.assistant.diagnostic.RuntimeLogger { *; }
-keep class com.assistant.diagnostic.AdapterSignalBus { *; }
-keep class com.assistant.diagnostic.registry.** { *; }
-keep class com.assistant.storage.SplendorStorageRoot { *; }

# 2. Kotlin Coroutines & Flow (CRITICAL MISSING)
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.flow.** { *; }

# 3. AndroidX Lifecycle & ViewModel (CRITICAL MISSING)
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(androidx.lifecycle.SavedStateHandle);
}

# 4. ML Kit / TensorFlow Lite (Vision/SmartAssist)
-keep class org.tensorflow.** { *; }
-keep class com.google.mlkit.** { *; }
-keepattributes *Annotation*

# 5. Accessibility Service & Reflection
-keep class * extends android.accessibilityservice.AccessibilityServiceInfo { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# 6. VPN Service (PingEliminator)
-keep class * extends android.net.VpnService { *; }
