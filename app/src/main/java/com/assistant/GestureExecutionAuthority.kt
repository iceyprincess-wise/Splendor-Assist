package com.assistant

// F49_PATCH_V1

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription













import android.os.Handler











import com.assistant.diagnostic.RuntimeLogger








import com.assistant.execution.ExecutionSource
import com.assistant.overlay.interceptor.PerSourceTouchPolicy
import com.assistant.runtime.*
























import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























object GestureExecutionAuthority {

    private val requested = AtomicLong(0L)
    private val accepted = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val correlationCounter = AtomicLong(1000L)

    @Volatile private var lastOrigin: String = "none"
    @Volatile private var lastAccepted: Boolean = false
    @Volatile private var lastUpdatedMs: Long = 0L
    // CAPTUREFIX-3: the refusing gate of the last execute() call, for
    // the BUS_DISPATCH reason field (read by
    // SmartAssistAccessibilityEngine). Set at every false return:
    // touch-gate / dispatch-false / exception. "none" means the last
    // call was not refused by this authority.
    @Volatile var lastRejectReason: String = "none"
        private set
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
    }

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
        origin: String = "unattributed",
        // Phase 0 #3 (PerSourceTouchPolicy): the dispatch source, for the
        // per-source touch gate. Null = undeclared (fail-closed: treated as
        // SMART_ASSIST, which requires touch). SAAE passes request.source;
        // legacy callers that don't pass it get the fail-closed default.
        source: ExecutionSource? = null,
        actionIdParam: String? = null,
        busAcceptedParam: Boolean = false,
        busConsumedParam: Boolean = false
    ): Boolean {
        // F49 hard package deny: FORCE_ON cannot authorize gestures outside the game package.
        if (!com.assistant.VisionTrust.isExecutionPackageAllowed()) {
            com.assistant.diagnostic.RuntimeLogger.log("GestureExecutionAuthority rejected: package deny gate", "SMART_ASSIST")
            // F37-D2: was a silent return — deaths invisible in EXEC.
            rejected.incrementAndGet()
            return false
        }
        // FINAL EXECUTION GATE: preserve own-UI and central foreground policy checks.
        if (!com.assistant.VisionTrust.isGameForeground()) {
            com.assistant.diagnostic.RuntimeLogger.log("GestureExecutionAuthority rejected: game not foreground", "SMART_ASSIST")
            // F37-D2: was a silent return — deaths invisible in EXEC.
            rejected.incrementAndGet()
            return false
        }

        requested.incrementAndGet()
        lastOrigin = origin
        lastUpdatedMs = System.currentTimeMillis()

        if (com.assistant.execution.UiExecutionGate.blocked()) {
            // SPLENDOR_P11_D01: was a silent discard — requested was counted
            // but neither accepted nor rejected, so execution counters
            // under-reported rejections.
            rejected.incrementAndGet()
            RuntimeLogger.log("GestureExecutionAuthority rejected: UiExecutionGate blocked", "SMART_ASSIST")
            return false
        }
        val actionId = actionIdParam ?: createCorrelationId()

        val wrappedCallback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // TOUCHSOURCE-DISCRIMINATOR (Phase 0 #1): close the dispatch
                // window so touch events arriving after our gesture ends are
                // no longer attributed to us (guard margin still applies).
                TouchSourceDiscriminator.completeDispatch(actionId, System.currentTimeMillis())
                RuntimeLogger.log("GestureExecutionAuthority gesture completed actionId=$actionId", "SMART_ASSIST")
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
                // TOUCHSOURCE-DISCRIMINATOR (Phase 0 #1): close the dispatch
                // window on cancel too — a cancelled gesture is no longer
                // live, and its events must not be attributed to us.
                TouchSourceDiscriminator.completeDispatch(actionId, System.currentTimeMillis())
                RuntimeLogger.log("GestureExecutionAuthority gesture cancelled actionId=$actionId; cancelling outcome verification", "SMART_ASSIST")
                ActionOutcomeVerifier.cancelDispatch(actionId)
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

        // Phase 0 #3 (PerSourceTouchPolicy): the touch gate is per-source,
        // not global. KEEPER/INTERCEPTION are autonomous (unless the user
        // revoked autonomy via the ModMenu switch); SMART_ASSIST/STUTTER
        // and undeclared sources always require touch. Touch signal is
        // isUserTouchAuthorizing() (Split-boolean piece, Phase 0 #2) — the
        // 500 ms post-lift retention and 5 s stuck-true cap apply.
        val effectiveSource = source ?: ExecutionSource.SMART_ASSIST
        if (PerSourceTouchPolicy.requiresTouch(effectiveSource) && !UserTouchState.isUserTouchAuthorizing()) {
            RuntimeLogger.log(
                "Gesture blocked: no active user touch "
                    + "(source=$effectiveSource, "
                    + "isTouching=${UserTouchState.isTouching()}) "
                    + "GestureExecutionAuthority.kt dispatch gate",
                "SMART_ASSIST"
            )
            // SPLENDOR_F33_GATE_COUNTERS: refusals were invisible in EXEC counters.
            rejected.incrementAndGet()
            // CAPTUREFIX-3: name the refusing gate for the BUS_DISPATCH
            // reason field.
            lastRejectReason = "touch-gate"
            return false
        }

        return try {
            // TOUCHSOURCE-DISCRIMINATOR (Phase 0 #1): capture the dispatch
            // start so the window covers the whole live gesture.
            val dispatchStartMs = System.currentTimeMillis()
            val result = service.dispatchGesture(gesture, wrappedCallback, handler)
            // SPLENDOR_F31_EVIDENCE: dispatch boundary state for every attempt.
            RuntimeLogger.log(
                "dispatchGesture result=$result actionId=$actionId "
                    + "source=$effectiveSource "
                    + "isTouching=${UserTouchState.isTouching()} "
                    + "GestureExecutionAuthority.kt dispatchGesture boundary",
                "SMART_ASSIST"
            )
            if (result) {
                accepted.incrementAndGet()
                // Anchor outcome verification to actual dispatch boundary
                ActionOutcomeVerifier.confirmDispatch(actionId)
                // TOUCHSOURCE-DISCRIMINATOR (Phase 0 #1): register the
                // dispatch window. Only on result==true — a rejected
                // dispatch never becomes a live gesture.
                TouchSourceDiscriminator.registerDispatch(actionId, dispatchStartMs)
            } else {
                rejected.incrementAndGet()
            }
            
            val telemetry = ActionTelemetry(
                actionId = actionId,
                engine = origin,
                contributor = origin,
                actionClass = "TOUCH_GESTURE",
                intent = "DYNAMIC_STROBE_INJECTION",
                requestCreated = lastUpdatedMs,
                busAccepted = busAcceptedParam,
                busConsumed = busConsumedParam,
                backendSelected = "ACCESSIBILITY_DISPATCH",
                backendInvoked = true,
                osDispatchAccepted = result,
                gestureCompleted = false,
                gestureCancelled = false,
                gestureFailed = !result,
                effectObserved = false,
                effectNotObserved = false
            )
            updateTelemetryMap(telemetry)

            try {
                com.assistant.events.GameplayEventHub.emit(
                    if (result) "dispatch-accepted" else "dispatch-rejected",
                    "origin=$origin,actionId=$actionId"
                )
            } catch (_: Throwable) {
            }
            lastAccepted = result
            // CAPTUREFIX-3: the OS refused the gesture (or the dispatch
            // returned false without the touch gate firing).
            if (!result) lastRejectReason = "dispatch-false"
            result
        } catch (e: Exception) {
            failed.incrementAndGet()
            lastAccepted = false
            // CAPTUREFIX-3: name the refusing gate for the BUS_DISPATCH
            // reason field.
            lastRejectReason = "exception"
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
    )

    fun reset() {
        requested.set(0L)
        accepted.set(0L)
        rejected.set(0L)
        failed.set(0L)
        currentActionTelemetry = null
        lastActionTelemetry = null
        lastOrigin = "none"
        lastAccepted = false
        lastUpdatedMs = 0L
    }
}

/* ========
GoalDetectionResult
======== */
