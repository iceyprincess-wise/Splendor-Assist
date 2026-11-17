# ==========================================
# CORE MODULE CONSUMER RULES (AGGREGATED)
# ==========================================
# These rules only take effect in a minified build. The app module
# enables R8 in release (isMinifyEnabled = true), so the core rules
# merge into the release shrink; debug builds do not minify and these
# rules are inert there (No50-D7).

# 1. Retain Core Engine Interfaces & Data Classes
# Justified by REFLECTION, not by direct references (No50-D4):
# GlobalCrashHandler reflectively loads engine classes by name —
# Class.forName("com.assistant.runtime.GameplayEngineRegistry") (:350)
# and Class.forName(e.cls) for arbitrary registered engine classes
# (:453) — and WatchdogAdapterService does Class.forName(className)
# (:171). R8 cannot see those names, so the broad keeps stay.
-keep interface com.assistant.runtime.** { *; }
-keep class com.assistant.runtime.** { *; }
-keep class com.assistant.execution.** { *; }
-keep class com.assistant.diagnostic.RuntimeLogger { *; }
-keep class com.assistant.diagnostic.AdapterSignalBus { *; }
-keep class com.assistant.diagnostic.registry.** { *; }
-keep class com.assistant.storage.SplendorStorageRoot { *; }

# 2. Kotlin Coroutines & Flow
# (Was headed "CRITICAL MISSING" — wrong: coroutines are present and
# used, kotlinx-coroutines 1.7.3 in both modules; No50-D3.)
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.flow.** { *; }

# 3. AndroidX Lifecycle — REMOVED (No50-D2)
# The old ViewModel keeps are deleted: no ViewModel subclass exists in
# either module (repo-wide grep finds zero; the app uses only
# androidx.lifecycle.lifecycleScope in two activities, which needs no
# ViewModel keep). If a ViewModel is ever added, its keep must come
# back with it — ViewModelProvider instantiates by reflection and R8
# cannot see the constructor.

# 4. ML Kit / TensorFlow Lite (Vision/SmartAssist)
# Justified: the APP module uses both — TrainedDetectionEngine imports
# org.tensorflow.lite.Interpreter and app/build.gradle.kts declares
# ML Kit text-recognition + LiteRT. The app's own proguard-rules.pro
# already keeps org.tensorflow.lite.** too; the overlap is harmless
# (No50-D5).
-keep class org.tensorflow.** { *; }
-keep class com.google.mlkit.** { *; }
-keepattributes *Annotation*

# 5. Accessibility Service
# (No50-D1) The old rule "-keep class * extends
# android.accessibilityservice.AccessibilityServiceInfo" is deleted:
# it matched zero classes — nothing in the repo extends
# AccessibilityServiceInfo (verified: no subclass exists; javap shows
# it is a plain public class, and the repo has no extends of it).
# The AccessibilityService keep stays: SmartAssistAccessibilityEngine
# extends it (the manifest reference keeps it too; this is explicit).
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# 6. VPN Service (PingEliminator)
-keep class * extends android.net.VpnService { *; }
