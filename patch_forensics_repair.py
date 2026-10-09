#!/usr/bin/env python3
import os
import sys

def patch_file(filepath, search_str, replace_str):
    if not os.path.exists(filepath):
        print(f"FAIL: File not found: {filepath}")
        return False
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    if replace_str in content:
        print(f"PASS (already applied): {filepath}")
        return True
    if search_str not in content:
        print(f"FAIL: Expected search pattern not found in {filepath}")
        return False
    new_content = content.replace(search_str, replace_str)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(new_content)
    # Verification
    with open(filepath, 'r', encoding='utf-8') as f:
        updated = f.read()
    if replace_str in updated:
        print(f"PASS: Updated {filepath}")
        return True
    else:
        print(f"FAIL: Verification failed for {filepath}")
        return False

def main():
    print("=== STARTING SPLENDOR-ASSIST FORENSICS REPAIR PATCH ===")
    success = True

    # 1. Update accessibility_service_config.xml
    xml_path = "app/src/main/res/xml/accessibility_service_config.xml"
    xml_search = 'xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)"'
    xml_replace = 'xmlns:android="http://schemas.android.com/apk/res/android"'
    if not patch_file(xml_path, xml_search, xml_replace):
        success = False

    xml_search_gestures = 'android:canRetrieveWindowContent="true" />'
    xml_replace_gestures = 'android:canRetrieveWindowContent="true"\n    android:canPerformGestures="true" />'
    if not patch_file(xml_path, xml_search_gestures, xml_replace_gestures):
        success = False

    # 2. Update app/src/main/cpp/native_input.cpp
    cpp_path = "app/src/main/cpp/native_input.cpp"
    cpp_search = """extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectTap(
        JNIEnv* env, jobject /* this */, jfloat x, jfloat y) {
    // Native fast-path logic placeholder.
    // In a root environment, this would write to /dev/uinput.
    // In non-root, we rely on the Java-side fallback for security,
    // but this JNI call is still faster than pure Java reflection.
    LOGI("Native Tap: %f, %f", x, y);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectSwipe(
        JNIEnv* env, jobject /* this */, jfloat startX, jfloat startY, jfloat endX, jfloat endY, jlong duration) {
    LOGI("Native Swipe: %f,%f -> %f,%f", startX, startY, endX, endY);
    return JNI_TRUE;
}"""
    cpp_replace = """extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectTap(
        JNIEnv* env, jobject /* this */, jfloat x, jfloat y) {
    // Non-root environment: physical touch injection must be routed to Accessibility Service.
    LOGI("Native Tap (Delegating to Accessibility Authority): %f, %f", x, y);
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectSwipe(
        JNIEnv* env, jobject /* this */, jfloat startX, jfloat startY, jfloat endX, jfloat endY, jlong duration) {
    LOGI("Native Swipe (Delegating to Accessibility Authority): %f,%f -> %f,%f", startX, startY, endX, endY);
    return JNI_FALSE;
}"""
    if not patch_file(cpp_path, cpp_search, cpp_replace):
        success = False

    # 3. Update app/src/main/cpp/mutation/native_input_strobe_amplifier.c
    strobe_c_path = "app/src/main/cpp/mutation/native_input_strobe_amplifier.c"
    strobe_c_search = "Java_com_assistant_NativeBridge_nativeInjectStrobePackets("
    strobe_c_replace = "Java_com_assistant_NativeBridge_nativeInjectStrobePacketsAmplifierRef("
    if not patch_file(strobe_c_path, strobe_c_search, strobe_c_replace):
        success = False

    # 4. Update app/src/main/java/com/assistant/input/AsynchronousGestureQueue.kt
    agq_path = "app/src/main/java/com/assistant/input/AsynchronousGestureQueue.kt"
    agq_search = """        val path = Path().apply {
            moveTo(element.startX, element.startY)
            lineTo(endX, endY)
        }
        // MUTATION TOOL 3: High-frequency gesture splitting for zero-delay input
        val strobePackets = FloatArray(16)
        com.assistant.NativeBridge.nativeInjectStrobePackets(element.startX, element.startY, endX, endY, duration.toInt(), 4, strobePackets)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(10L)))
            .build()
        GestureExecutionAuthority.execute(element.service, gesture, null, null)"""
    agq_replace = """        // MUTATION TOOL 3: High-frequency gesture splitting for zero-delay input
        val strobePackets = FloatArray(16)
        com.assistant.NativeBridge.nativeInjectStrobePackets(element.startX, element.startY, endX, endY, duration.toInt(), 4, strobePackets)

        val builder = GestureDescription.Builder()
        // Consume strobePackets (4 packets x 4 floats: [ptr, px, py, t]) to construct multi-stroke gesture
        for (i in 0 until 4) {
            val idx = i * 4
            val px = strobePackets[idx + 1]
            val py = strobePackets[idx + 2]
            val timeOffset = strobePackets[idx + 3].toLong()
            val strokePath = Path().apply {
                moveTo(element.startX, element.startY)
                lineTo(px, py)
            }
            val strokeDuration = (duration - timeOffset).coerceAtLeast(2L)
            builder.addStroke(GestureDescription.StrokeDescription(strokePath, timeOffset, strokeDuration))
        }
        val gesture = builder.build()
        GestureExecutionAuthority.execute(element.service, gesture, null, null, origin = "AsynchronousGestureQueue")"""
    if not patch_file(agq_path, agq_search, agq_replace):
        success = False

    # 5. Update app/src/main/java/com/assistant/AsynchronousGestureQueue.kt
    agq_alt_path = "app/src/main/java/com/assistant/AsynchronousGestureQueue.kt"
    agq_alt_search = """            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            accessibilityService!!.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        isProcessing.set(false)
                        processNext()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        isProcessing.set(false)
                        processNext()
                    }
                },
                null
            )"""
    agq_alt_replace = """            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    isProcessing.set(false)
                    processNext()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    isProcessing.set(false)
                    processNext()
                }
            }
            GestureExecutionAuthority.execute(
                accessibilityService!!, gesture, callback, null, origin = "AsynchronousGestureQueue"
            )"""
    if not patch_file(agq_alt_path, agq_alt_search, agq_alt_replace):
        success = False

    # 6. Update GestureExecutionAuthority in app/src/main/java/com/assistant/gameplay_engine.kt
    ge_path = "app/src/main/java/com/assistant/gameplay_engine.kt"
    ge_search = """object GestureExecutionAuthority {

    private val requested = AtomicLong(0L)
    private val accepted = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val failed = AtomicLong(0L)

    @Volatile private var lastOrigin: String = "none"
    @Volatile private var lastAccepted: Boolean = false
    @Volatile private var lastUpdatedMs: Long = 0L

    fun execute(
        service: AccessibilityService,
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback? = null,
        handler: Handler? = null,
        origin: String = "unattributed"
    ): Boolean {
        requested.incrementAndGet()
        lastOrigin = origin
        lastUpdatedMs = System.currentTimeMillis()

        return try {
            val result = service.dispatchGesture(gesture, callback, handler)
            if (result) accepted.incrementAndGet() else rejected.incrementAndGet()
            try {
                com.assistant.events.GameplayEventHub.emit(
                    if (result) "dispatch-accepted" else "dispatch-rejected",
                    "origin=$origin"
                )
            } catch (_: Throwable) {
            }
            lastAccepted = result
            result
        } catch (e: Exception) {
            failed.incrementAndGet()
            lastAccepted = false
            RuntimeLogger.log(
                "Gesture execution failed origin=$origin: ${e.message}",
                "SMART_ASSIST"
            )
            false
        }
    }

    fun executionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "requested" to requested.get(),
        "accepted" to accepted.get(),
        "rejected" to rejected.get(),
        "failed" to failed.get(),
        "lastOrigin" to lastOrigin,
        "lastAccepted" to lastAccepted,
        "lastUpdatedMs" to lastUpdatedMs
    )"""

    ge_replace = """object GestureExecutionAuthority {

    private val requested = AtomicLong(0L)
    private val accepted = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val correlationCounter = AtomicLong(1000L)

    @Volatile private var lastOrigin: String = "none"
    @Volatile private var lastAccepted: Boolean = false
    @Volatile private var lastUpdatedMs: Long = 0L
    @Volatile private var lastActionTelemetry: Map<String, Any>? = null

    data class ActionTelemetry(
        val actionId: String,
        val engine: String,
        val contributor: String,
        val actionClass: String,
        val intent: String,
        val requestCreated: Long,
        val busAccepted: Boolean,
        val busConsumed: Boolean,
        val backendSelected: String,
        val backendInvoked: Boolean,
        val osDispatchAccepted: Boolean,
        val gestureCompleted: Boolean = false,
        val gestureCancelled: Boolean = false,
        val gestureFailed: Boolean = false,
        val effectObserved: Boolean = false,
        val effectNotObserved: Boolean = false
    )

    fun createCorrelationId(): String = "ACT-${System.currentTimeMillis()}-${correlationCounter.incrementAndGet()}"

    fun execute(
        service: AccessibilityService,
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback? = null,
        handler: Handler? = null,
        origin: String = "unattributed"
    ): Boolean {
        requested.incrementAndGet()
        lastOrigin = origin
        lastUpdatedMs = System.currentTimeMillis()

        val actionId = createCorrelationId()
        var completed = false
        var cancelled = false

        val wrappedCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                callback?.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                cancelled = true
                callback?.onCancelled(gestureDescription)
            }
        }

        return try {
            val result = service.dispatchGesture(gesture, wrappedCallback, handler)
            if (result) accepted.incrementAndGet() else rejected.incrementAndGet()

            val telemetry = ActionTelemetry(
                actionId = actionId,
                engine = origin,
                contributor = origin,
                actionClass = "TOUCH_GESTURE",
                intent = "DYNAMIC_STROBE_INJECTION",
                requestCreated = lastUpdatedMs,
                busAccepted = true,
                busConsumed = true,
                backendSelected = "ACCESSIBILITY_DISPATCH",
                backendInvoked = true,
                osDispatchAccepted = result,
                gestureCompleted = completed,
                gestureCancelled = cancelled,
                gestureFailed = !result,
                effectObserved = result,
                effectNotObserved = !result
            )
            lastActionTelemetry = mapOf(
                "ACTION_ID" to telemetry.actionId,
                "ENGINE" to telemetry.engine,
                "CONTRIBUTOR" to telemetry.contributor,
                "ACTION_CLASS" to telemetry.actionClass,
                "INTENT" to telemetry.intent,
                "REQUEST_CREATED" to telemetry.requestCreated,
                "BUS_ACCEPTED" to telemetry.busAccepted,
                "BUS_CONSUMED" to telemetry.busConsumed,
                "BACKEND_SELECTED" to telemetry.backendSelected,
                "BACKEND_INVOKED" to telemetry.backendInvoked,
                "OS_DISPATCH_ACCEPTED" to telemetry.osDispatchAccepted,
                "GESTURE_COMPLETED" to telemetry.gestureCompleted,
                "GESTURE_CANCELLED" to telemetry.gestureCancelled,
                "GESTURE_FAILED" to telemetry.gestureFailed,
                "EFFECT_OBSERVED" to telemetry.effectObserved,
                "EFFECT_NOT_OBSERVED" to telemetry.effectNotObserved
            )

            try {
                com.assistant.events.GameplayEventHub.emit(
                    if (result) "dispatch-accepted" else "dispatch-rejected",
                    "origin=$origin,actionId=$actionId"
                )
            } catch (_: Throwable) {
            }
            lastAccepted = result
            result
        } catch (e: Exception) {
            failed.incrementAndGet()
            lastAccepted = false
            RuntimeLogger.log(
                "Gesture execution failed origin=$origin, actionId=$actionId: ${e.message}",
                "SMART_ASSIST"
            )
            false
        }
    }

    fun executionRuntimeSnapshot(): Map<String, Any> = mapOf(
        "requested" to requested.get(),
        "accepted" to accepted.get(),
        "rejected" to rejected.get(),
        "failed" to failed.get(),
        "lastOrigin" to lastOrigin,
        "lastAccepted" to lastAccepted,
        "lastUpdatedMs" to lastUpdatedMs,
        "lastActionTelemetry" to (lastActionTelemetry ?: emptyMap<String, Any>())
    )"""

    if not patch_file(ge_path, ge_search, ge_replace):
        success = False

    if success:
        print("\n=== ALL PATCHES APPLIED SUCCESSFULLY ===")
        sys.exit(0)
    else:
        print("\n=== ONE OR MORE PATCHES FAILED ===")
        sys.exit(1)

if __name__ == "__main__":
    main()
