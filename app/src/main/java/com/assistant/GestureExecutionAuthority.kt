object GestureExecutionAuthority {

    private val requested = AtomicLong(0L)
    private val accepted = AtomicLong(0L)
    private val rejected = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val correlationCounter = AtomicLong(1000L)

    @Volatile private var lastOrigin: String = "none"
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
        actionIdParam: String? = null,
        busAcceptedParam: Boolean = false,
        busConsumedParam: Boolean = false
    ): Boolean {
        // FINAL EXECUTION GATE: Reject if intended game is not foregrounded
        if (!com.assistant.VisionTrust.isGameForeground()) {
            com.assistant.diagnostic.RuntimeLogger.log("GestureExecutionAuthority rejected: game not foreground", "SMART_ASSIST")
            return false
        }

        requested.incrementAndGet()
        lastOrigin = origin
        lastUpdatedMs = System.currentTimeMillis()

        val actionId = actionIdParam ?: createCorrelationId()

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

        if (!InterceptionRuntimeRegistry.bypassTouchGate && !UserTouchState.isTouching()) {
            RuntimeLogger.log(
                "Gesture blocked: no active user touch "
                    + "(bypassTouchGate=${InterceptionRuntimeRegistry.bypassTouchGate}, "
                    + "isTouching=${UserTouchState.isTouching()}) "
                    + "app/src/main/java/com/assistant/gameplay_engine.kt dispatch gate",
                "SMART_ASSIST"
            )
            // SPLENDOR_F33_GATE_COUNTERS: refusals were invisible in EXEC counters.
            rejected.incrementAndGet()
            return false
        }

        return try {
            val result = service.dispatchGesture(gesture, wrappedCallback, handler)
            // SPLENDOR_F31_EVIDENCE: dispatch boundary state for every attempt.
            RuntimeLogger.log(
                "dispatchGesture result=$result actionId=$actionId "
                    + "bypassTouchGate=${InterceptionRuntimeRegistry.bypassTouchGate} "
                    + "isTouching=${UserTouchState.isTouching()} "
                    + "app/src/main/java/com/assistant/gameplay_engine.kt dispatchGesture boundary",
                "SMART_ASSIST"
            )
            if (result) {
                accepted.incrementAndGet()
                // Anchor outcome verification to actual dispatch boundary
                ActionOutcomeVerifier.confirmDispatch(actionId)
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
    )

    fun reset() {
        requested.set(0L)
        accepted.set(0L)
        rejected.set(0L)
        failed.set(0L)
        lastOrigin = "none"
        lastAccepted = false
        lastUpdatedMs = 0L
    }
}
/* ======
GestureExecutionAuthority Anchor
====== */

/* ========
GoalDetectionResult
======== */
