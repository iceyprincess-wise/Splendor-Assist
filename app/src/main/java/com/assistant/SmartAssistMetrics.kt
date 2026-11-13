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
    val trajectoryProduced = AtomicLong()

    fun submitRequest() {
        requestsSubmitted.incrementAndGet()
    }

    fun executeRequest() {
        requestsExecuted.incrementAndGet()
    }

    fun produceTrajectory() {
        trajectoryProduced.incrementAndGet()
    }

    fun snapshot(): String {
        return buildString {
            append("Submitted : ")
            append(requestsSubmitted.get())
            append("\n")
            append("Executed : ")
            append(requestsExecuted.get())
            append("\n")
            append("Trajectory : ")
            append(trajectoryProduced.get())
        }
    }

    fun reset() {
        requestsSubmitted.set(0L)
        requestsExecuted.set(0L)
        trajectoryProduced.set(0L)
    }

    fun magneticFeetRuntimeSnapshot(): Map<String, Any> {
        val state = MagneticFeetEngine.magneticFeetSnapshot()
        return mapOf(
            "sequence" to (state?.sequence ?: 0L),
            "amplification" to (state?.amplification ?: 1.0f),
            "touchRetention" to (state?.result?.touchRetention ?: 0.0f),
            "interceptionResistance" to (state?.result?.interceptionResistance ?: 0.0f),
            "possessionControl" to (state?.result?.possessionControl ?: 0.0f)
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
