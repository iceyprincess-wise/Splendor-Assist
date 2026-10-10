#!/usr/bin/env python3
import sys
import pathlib

print("=== SPLENDOR-ASSIST TELEMETRY PATCH SCRIPT ===")

target_file = pathlib.Path("app/src/main/java/com/assistant/gameplay_engine.kt")

if not target_file.is_file():
    print(f"FAIL: Target file missing: {target_file}")
    sys.exit(1)

content = target_file.read_text(encoding="utf-8")

# Search pattern 1: ActionOutcomeVerifier.verify
old_verifier_timeout = """        if (age > 350L) {
            effectNotObservedCount.incrementAndGet()
            pendingDispatch = null
            pendingFrame = null
            return
        }"""

new_verifier_timeout = """        if (age > 350L) {
            effectNotObservedCount.incrementAndGet()
            GestureExecutionAuthority.recordEffectObserved(observed = false)
            pendingDispatch = null
            pendingFrame = null
            return
        }"""

old_verifier_outcome = """        if (observed) {
            effectObservedCount.incrementAndGet()
        } else {
            effectNotObservedCount.incrementAndGet()
        }"""

new_verifier_outcome = """        if (observed) {
            effectObservedCount.incrementAndGet()
            GestureExecutionAuthority.recordEffectObserved(observed = true)
        } else {
            effectNotObservedCount.incrementAndGet()
            GestureExecutionAuthority.recordEffectObserved(observed = false)
        }"""

# Search pattern 2: GestureExecutionAuthority
old_authority_vars = """    @Volatile private var lastOrigin: String = "none"
    @Volatile private var lastAccepted: Boolean = false
    @Volatile private var lastUpdatedMs: Long = 0L
    @Volatile private var lastActionTelemetry: Map<String, Any>? = null"""

new_authority_vars = """    @Volatile private var lastOrigin: String = "none"
    @Volatile private var lastAccepted: Boolean = false
    @Volatile private var lastUpdatedMs: Long = 0L
    @Volatile private var currentActionTelemetry: ActionTelemetry? = null
    @Volatile private var lastActionTelemetry: Map<String, Any>? = null

    private fun updateTelemetryMap(telemetry: ActionTelemetry) {
        currentActionTelemetry = telemetry
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
    }

    fun recordEffectObserved(actionId: String? = null, observed: Boolean) {
        val curr = currentActionTelemetry ?: return
        if (actionId == null || curr.actionId == actionId) {
            val updated = curr.copy(
                effectObserved = observed,
                effectNotObserved = !observed
            )
            updateTelemetryMap(updated)
        }
    }"""

old_authority_execute = """        val actionId = createCorrelationId()
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
            )"""

new_authority_execute = """        val actionId = createCorrelationId()

        val wrappedCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val curr = currentActionTelemetry
                if (curr != null && curr.actionId == actionId) {
                    val updated = curr.copy(
                        gestureCompleted = true,
                        gestureCancelled = false,
                        gestureFailed = false
                    )
                    updateTelemetryMap(updated)
                }
                callback?.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                val curr = currentActionTelemetry
                if (curr != null && curr.actionId == actionId) {
                    val updated = curr.copy(
                        gestureCompleted = false,
                        gestureCancelled = true,
                        gestureFailed = true
                    )
                    updateTelemetryMap(updated)
                }
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
                gestureCompleted = false,
                gestureCancelled = false,
                gestureFailed = !result,
                effectObserved = false,
                effectNotObserved = false
            )
            updateTelemetryMap(telemetry)"""

# Verify preconditions
if old_verifier_timeout not in content:
    print("FAIL: Precondition failed: old_verifier_timeout not found")
    sys.exit(1)

if old_verifier_outcome not in content:
    print("FAIL: Precondition failed: old_verifier_outcome not found")
    sys.exit(1)

if old_authority_vars not in content:
    print("FAIL: Precondition failed: old_authority_vars not found")
    sys.exit(1)

if old_authority_execute not in content:
    print("FAIL: Precondition failed: old_authority_execute not found")
    sys.exit(1)

# Apply mutations
content = content.replace(old_verifier_timeout, new_verifier_timeout, 1)
content = content.replace(old_verifier_outcome, new_verifier_outcome, 1)
content = content.replace(old_authority_vars, new_authority_vars, 1)
content = content.replace(old_authority_execute, new_authority_execute, 1)

# Post-mutation verification
if "recordEffectObserved" not in content:
    print("FAIL: Post-verification failed: recordEffectObserved missing after patch")
    sys.exit(1)

if "effectObserved = false" not in content:
    print("FAIL: Post-verification failed: effectObserved = false missing after patch")
    sys.exit(1)

target_file.write_text(content, encoding="utf-8")
print("PASS: Patch applied and verified successfully.")
