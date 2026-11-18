package com.assistant


































import com.assistant.execution.ExecutionRequest



import com.assistant.runtime.*
























import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























object SmartAssistMetrics {
    data class GoalkeeperShadowDiagnostics(
        val observations: Long,
        val lastPhase: Int,
        val lastStartX: Float,
        val lastStartY: Float,
        val lastEndX: Float,
        val lastEndY: Float,
        val lastDuration: Long,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    private var goalkeeperShadowObservations: Long = 0L
    private var goalkeeperShadowLastPhase: Int = 0
    private var goalkeeperShadowLastStartX: Float = 0f
    private var goalkeeperShadowLastStartY: Float = 0f
    private var goalkeeperShadowLastEndX: Float = 0f
    private var goalkeeperShadowLastEndY: Float = 0f
    private var goalkeeperShadowLastDuration: Long = 0L
    private var goalkeeperShadowLastReason: String = "no goalkeeper shadow observation yet"
    private var goalkeeperShadowLastUpdatedMs: Long = 0L

    @Synchronized
    fun recordGoalkeeperShadow(request: ExecutionRequest, reason: String) {
        goalkeeperShadowObservations += 1L
        goalkeeperShadowLastPhase = request.phase
        goalkeeperShadowLastStartX = request.startX
        goalkeeperShadowLastStartY = request.startY
        goalkeeperShadowLastEndX = request.endX
        goalkeeperShadowLastEndY = request.endY
        goalkeeperShadowLastDuration = request.duration
        goalkeeperShadowLastReason = reason
        goalkeeperShadowLastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun goalkeeperShadowRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "observations" to goalkeeperShadowObservations,
            "lastPhase" to goalkeeperShadowLastPhase,
            "lastStartX" to goalkeeperShadowLastStartX,
            "lastStartY" to goalkeeperShadowLastStartY,
            "lastEndX" to goalkeeperShadowLastEndX,
            "lastEndY" to goalkeeperShadowLastEndY,
            "lastDuration" to goalkeeperShadowLastDuration,
            "lastReason" to goalkeeperShadowLastReason,
            "lastUpdatedMs" to goalkeeperShadowLastUpdatedMs
        )

    data class BusExecutionDiagnostics(
        val consumed: Long,
        val dispatched: Long,
        val failed: Long,
        val lastSource: String,
        val lastPhase: Int,
        val lastDuration: Long,
        val lastStartX: Float,
        val lastStartY: Float,
        val lastEndX: Float,
        val lastEndY: Float,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    private var busConsumed: Long = 0L
    private var busDispatched: Long = 0L
    private var busFailed: Long = 0L
    private var busLastSource: String = "none"
    private var busLastPhase: Int = 0
    private var busLastDuration: Long = 0L
    private var busLastStartX: Float = 0f
    private var busLastStartY: Float = 0f
    private var busLastEndX: Float = 0f
    private var busLastEndY: Float = 0f
    private var busLastReason: String = "no bus request consumed yet"
    private var busLastUpdatedMs: Long = 0L

    @Synchronized
    fun recordBusConsumed(request: ExecutionRequest) {
        busConsumed += 1L
        busLastSource = request.source.name
        busLastPhase = request.phase
        busLastDuration = request.duration
        busLastStartX = request.startX
        busLastStartY = request.startY
        busLastEndX = request.endX
        busLastEndY = request.endY
        busLastReason = "CentralExecutionBus request consumed by accessibility engine"
        busLastUpdatedMs = System.currentTimeMillis()
        submitRequest()
    }

    @Synchronized
    fun recordBusDispatchResult(request: ExecutionRequest, dispatched: Boolean) {
        if (dispatched) {
            busDispatched += 1L
            busLastReason = "Gesture dispatched from consumed bus request"
        } else {
            busFailed += 1L
            busLastReason = "Gesture dispatch failed from consumed bus request"
        }
        busLastSource = request.source.name
        busLastPhase = request.phase
        busLastDuration = request.duration
        busLastUpdatedMs = System.currentTimeMillis()
    }

    @Synchronized
    fun busExecutionRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "consumed" to busConsumed,
            "dispatched" to busDispatched,
            "failed" to busFailed,
            "lastSource" to busLastSource,
            "lastPhase" to busLastPhase,
            "lastDuration" to busLastDuration,
            "lastStartX" to busLastStartX,
            "lastStartY" to busLastStartY,
            "lastEndX" to busLastEndX,
            "lastEndY" to busLastEndY,
            "lastReason" to busLastReason,
            "lastUpdatedMs" to busLastUpdatedMs
        )

    fun gameplayDownstreamRuntimeSnapshot(): Map<String, Any> {
        val event = GameplayDecisionEngine.gameplayDownstreamSnapshot()
        return mapOf(
            "sequence" to (event?.sequence ?: 0L),
            "source" to (event?.source ?: "none"),
            "lastAuthority" to (event?.authority ?: 0f),
            "active" to (event != null)
        )
    }

    fun gameplayAmplificationRuntimeSnapshot(): Map<String, Number> {
        val snapshot = GameplayDecisionEngine.gameplayAmplificationSnapshot()
        return mapOf(
            "decisionCycles" to snapshot.first,
            "lastAuthority" to snapshot.second
        )
    }

    val requestsSubmitted = AtomicLong()
    val requestsExecuted = AtomicLong()
    // No68-E2-D3: the trajectory counter / producer are gone —
    // zero callers repo-wide, no producer defined anywhere, so the
    // "Trajectory" line of snapshot() printed a permanent 0.

    fun submitRequest() {
        requestsSubmitted.incrementAndGet()
    }

    fun executeRequest() {
        requestsExecuted.incrementAndGet()
    }

    fun snapshot(): String {
        return buildString {
            append("Submitted : ")
            append(requestsSubmitted.get())
            append("\n")
            append("Executed : ")
            append(requestsExecuted.get())
        }
    }

    // No68-E2-D1: the old reset() zeroed only the three request counters
    // while RuntimeCoordinator.reset() calls it expecting a full session
    // reset — bus counts, goalkeeper-shadow state and the decision
    // mirror all survived into the next epoch and the dashboard mixed
    // epochs (same class as No66-D40). Everything resets now.
    @Synchronized
    fun reset() {
        requestsSubmitted.set(0L)
        requestsExecuted.set(0L)
        goalkeeperShadowObservations = 0L
        goalkeeperShadowLastPhase = 0
        goalkeeperShadowLastStartX = 0f
        goalkeeperShadowLastStartY = 0f
        goalkeeperShadowLastEndX = 0f
        goalkeeperShadowLastEndY = 0f
        goalkeeperShadowLastDuration = 0L
        goalkeeperShadowLastReason = "no goalkeeper shadow observation yet"
        goalkeeperShadowLastUpdatedMs = 0L
        busConsumed = 0L
        busDispatched = 0L
        busFailed = 0L
        busLastSource = "none"
        busLastPhase = 0
        busLastDuration = 0L
        busLastStartX = 0f
        busLastStartY = 0f
        busLastEndX = 0f
        busLastEndY = 0f
        busLastReason = "no bus request consumed yet"
        busLastUpdatedMs = 0L
        decisionsMade.set(0L)
        decisionActions.set(0L)
        decisionErrors.set(0L)
        decisionTimeTotal.set(0L)
        lastDecisionMs = 0L
        lastDecisionUpdatedMs = 0L
    }

    fun magneticFeetRuntimeSnapshot(): Map<String, Any> {
        // No68-SYNTH (warning cleanup): magneticFeetSnapshot() is non-null
        // since No68-B-D41, and MagneticFeetState.result + its fields are
        // non-null too — the defensive ?. / ?: here warned on every line
        // (9 warnings in the first :app:compileDebugKotlin run). Direct
        // access; no behaviour change.
        val state = MagneticFeetEngine.magneticFeetSnapshot()
        return mapOf(
            "sequence" to state.sequence,
            "amplification" to state.amplification,
            "touchRetention" to state.result.touchRetention,
            "interceptionResistance" to state.result.interceptionResistance,
            "possessionControl" to state.result.possessionControl
        )
    }

    fun crossingLaneRuntimeSnapshot(): Map<String, Any> {
        val state = CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        val lanes = state?.result?.lanes.orEmpty()
        return mapOf(
            "sequence" to (state?.sequence ?: 0L),
            "laneCount" to lanes.size,
            "viableLaneCount" to lanes.count { it.viable },
            "bestConfidence" to (lanes.maxOfOrNull { it.confidence } ?: 0.0f)
        )
    }

    fun magneticFeetActivationRuntimeSnapshot(): Map<String, Any> {
        val state = MagneticFeetEngine.magneticFeetActivationDiagnostics()
        return mapOf(
            "calls" to state.calls,
            "lastPressure" to state.lastPressure,
            "lastStrength" to state.lastStrength,
            "lastReason" to state.lastReason,
            // SPLENDOR_P10_TRAP_DIAG_METRICS
            "lastTrapWindow" to state.lastTrapWindow,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    fun gameplayActivationRuntimeSnapshot(): Map<String, Any> {
        val state = GameplayDecisionEngine.gameplayActivationDiagnostics()
        return mapOf(
            "adaptiveModeCalls" to state.adaptiveModeCalls,
            "decideCalls" to state.decideCalls,
            "lastHasBall" to state.lastHasBall,
            "lastMode" to state.lastMode,
            "lastStrength" to state.lastStrength,
            "lastReason" to state.lastReason,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    fun controllerEntryRuntimeSnapshot(): Map<String, Any> {
        val state = ActiveGestureControllerDiagnostics.snapshot()
        return mapOf(
            "entryCalls" to state.entryCalls,
            "blockedCalls" to state.blockedCalls,
            "lastReason" to state.lastReason,
            "lastStartX" to state.lastStartX,
            "lastStartY" to state.lastStartY,
            "lastEndX" to state.lastEndX,
            "lastEndY" to state.lastEndY,
            "lastDuration" to state.lastDuration,
            "lastUpdatedMs" to state.lastUpdatedMs
        )
    }

    // ==== Decision mirror ====
    // Written by GameplayDecisionEngine.decide() via noteDecision(), so that
    // decision-level counters and request-level counters share one reader.
    // (The overlay-metrics duplicate object that used to claim this wiring
    // never called it; it is deleted in patch 4.)
    private val decisionsMade = AtomicLong()
    private val decisionActions = AtomicLong()
    private val decisionErrors = AtomicLong()
    private val decisionTimeTotal = AtomicLong()
    @Volatile private var lastDecisionMs: Long = 0L
    @Volatile private var lastDecisionUpdatedMs: Long = 0L

    // Called by GameplayDecisionEngine.decide() once per decision with the
    // real measured decide() duration. decisionActions is intentionally NOT
    // incremented here: no action-trigger signal exists at the decide() call
    // site, so it stays 0 until a caller that knows whether an action fired
    // reports it. decisionErrors likewise has no producer yet.
    fun noteDecision(durationMs: Long) {
        decisionsMade.incrementAndGet()
        decisionTimeTotal.addAndGet(durationMs)
        lastDecisionMs = durationMs
        lastDecisionUpdatedMs = System.currentTimeMillis()
    }

    // No68-E2-D2: the action/error hooks. decisionActions and
    // decisionErrors had no producer anywhere, so the snapshot published
    // permanent 0s as if measured. These are the wire points — the
    // call-site wiring (dispatch path) is QE2-5; until it lands the
    // fields stay 0, now honestly documented as unwired rather than
    // silently presented as measured.
    fun noteActionTriggered() {
        decisionActions.incrementAndGet()
    }

    fun noteDecisionError() {
        decisionErrors.incrementAndGet()
    }

    fun decisionRuntimeSnapshot(): Map<String, Any> {
        val made = decisionsMade.get()
        return mapOf(
            "decisionsMade" to made,
            "actionsTriggered" to decisionActions.get(),
            "errorCount" to decisionErrors.get(),
            "avgDecisionTimeMs" to if (made > 0L) decisionTimeTotal.get() / made else 0L,
            "lastDecisionTimeMs" to lastDecisionMs,
            "lastUpdatedMs" to lastDecisionUpdatedMs
        )
    }

}

/* ========
TacticalBehaviorRecognitionResult
======== */
