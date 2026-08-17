package com.assistant.adapter.smartassist

import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry

/**
 * Immutable runtime observation consumed by InAppAgentCore.
 *
 * This is deliberately a snapshot:
 * gameplay engines remain owners of gameplay state;
 * the agent only observes authoritative runtime surfaces.
 */
data class RuntimeObservation(
    val timestampMs: Long,
    val health: RuntimeHealthMonitor.HealthState,
    val decisions: Long,
    val routed: Long,
    val loadShed: String,
    val selfHealRunning: Boolean,
    val selfHealStatus: String,
    val totalHeals: Int,
    val performanceAuthority: Int
) {
    companion object {
        fun capture(): RuntimeObservation {
            val health = RuntimeHealthMonitor.snapshot()
            val decision = RuntimeDecisionLoop.decisionRuntimeSnapshot()

            val decisions =
                (decision["decisions"] as? Number)?.toLong() ?: 0L

            val routed =
                (decision["routed"] as? Number)?.toLong() ?: 0L

            val loadShed =
                try {
                    PerformanceTelemetryRegistry.currentLoadShed()
                } catch (_: Throwable) {
                    "UNKNOWN"
                }

            return RuntimeObservation(
                timestampMs = System.currentTimeMillis(),
                health = health,
                decisions = decisions,
                routed = routed,
                loadShed = loadShed,
                selfHealRunning = RuntimeSelfHealEngine.isRunning(),
                selfHealStatus = RuntimeSelfHealEngine.agentStatus,
                totalHeals = RuntimeSelfHealEngine.totalHeals,
                performanceAuthority = RuntimePerformanceCoordinator.authority()
            )
        }
    }
}
