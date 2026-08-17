package com.assistant.adapter.smartassist

data class AgentDecision(
    val action: AgentAction,
    val priority: Int,
    val reason: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Deterministic first-generation agent policy.
 *
 * It is intentionally evidence-driven:
 * no action is taken unless an existing runtime health surface
 * provides a concrete reason.
 */
object AgentDecisionPolicy {

    fun decide(observation: RuntimeObservation): AgentDecision {
        val health = observation.health

        if (!health.accessibilityAlive) {
            return AgentDecision(
                action = AgentAction.ObserveOnly,
                priority = 0,
                reason =
                    "Accessibility runtime is not ready; " +
                    "agent must not attempt gameplay recovery."
            )
        }

        if (!health.overlayAlive || !health.frameAlive) {
            return AgentDecision(
                action = AgentAction.RunSelfHealCheck,
                priority = 100,
                reason = "Capture/overlay path is not healthy."
            )
        }

        if (!health.decisionAlive && health.frameAlive) {
            return AgentDecision(
                action = AgentAction.RunSelfHealCheck,
                priority = 90,
                reason = "Frames are flowing but RuntimeDecisionLoop is stale."
            )
        }

        if (observation.loadShed == "HEAVY") {
            return AgentDecision(
                action = AgentAction.RefreshPerformance,
                priority = 70,
                reason =
                    "Runtime load-shed is HEAVY; " +
                    "refresh performance telemetry and state."
            )
        }

        return AgentDecision(
            action = AgentAction.ObserveOnly,
            priority = 10,
            reason =
                if (health.degradedReasons.isEmpty()) {
                    "Runtime healthy; observation only."
                } else {
                    "Runtime degraded without a safe automated recovery action."
                }
        )
    }
}
