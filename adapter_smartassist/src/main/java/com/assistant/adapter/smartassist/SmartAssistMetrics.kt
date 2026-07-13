package com.assistant.adapter.smartassist

import java.util.concurrent.atomic.AtomicLong
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource

object SmartAssistMetrics {
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

}