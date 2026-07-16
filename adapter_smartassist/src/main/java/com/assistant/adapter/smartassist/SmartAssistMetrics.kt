package com.assistant.adapter.smartassist

import java.util.concurrent.atomic.AtomicLong
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource

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
            "amplification" to (event?.amplification ?: 1000000.0f),
            "active" to (event != null)
        )
    }

    fun gameplayAmplificationRuntimeSnapshot(): Map<String, Number> {
        val snapshot = GameplayDecisionEngine.gameplayAmplificationSnapshot()
        return mapOf(
            "amplification" to 1000000.0f,
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
            "amplification" to (state?.amplification ?: 1000000.0f),
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
            "amplification" to (state?.amplification ?: 1000000.0f),
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


    private var gameplayHeartbeatTicks: Long = 0L
    private var gameplayHeartbeatLastReason: String = "not started"
    private var gameplayHeartbeatLastUpdatedMs: Long = 0L

    @Synchronized
    fun runGameplayHeartbeat(reason: String = "diagnosis heartbeat") {
        gameplayHeartbeatTicks += 1L
        gameplayHeartbeatLastReason = reason
        gameplayHeartbeatLastUpdatedMs = System.currentTimeMillis()

        val crossing = CrossingLaneAnalysisEngine.crossingLaneAnalysisEngineSnapshot()
        val laneCount = crossing?.result?.lanes?.size ?: 0
        val bestConfidence = crossing?.result?.lanes?.maxOfOrNull { it.confidence } ?: 0.0f
        val syntheticPressure = (laneCount * 10).coerceIn(0, 100)
        val syntheticStrength = ((bestConfidence * 100.0f).toInt()).coerceIn(0, 100)

        MagneticFeetEngine.stabilize(
            pressure = syntheticPressure,
            strength = syntheticStrength
        )

        val temporal = TemporalMemoryState(
            temporalConfidence = bestConfidence.coerceIn(0.0f, 1.0f),
            exponentialMovingAverage = bestConfidence.coerceIn(0.0f, 1.0f),
            rollingMean = bestConfidence.coerceIn(0.0f, 1.0f),
            historyStability = 1.0f,
            confidenceVariance = 0.0f,
            confidenceTrend = 0.0f
        )

        GameplayDecisionEngine.selectVisionAdaptiveMode(
            hasBall = laneCount > 0,
            shotAuthority = bestConfidence.coerceIn(0.0f, 1.0f),
            passAuthority = bestConfidence.coerceIn(0.0f, 1.0f),
            crossAuthority = bestConfidence.coerceIn(0.0f, 1.0f),
            visionConfidence = bestConfidence.coerceIn(0.0f, 1.0f),
            tacticalConfidence = bestConfidence.coerceIn(0.0f, 1.0f),
            intelligenceConfidence = bestConfidence.coerceIn(0.0f, 1.0f),
            runtimeCalibration = 1.0f,
            onlineAdaptation = 1.0f,
            temporal = temporal
        )
    }

    fun gameplayHeartbeatRuntimeSnapshot(): Map<String, Any> =
        mapOf(
            "ticks" to gameplayHeartbeatTicks,
            "lastReason" to gameplayHeartbeatLastReason,
            "lastUpdatedMs" to gameplayHeartbeatLastUpdatedMs
        )

}
