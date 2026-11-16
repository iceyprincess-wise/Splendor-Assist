package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object AgentDecisionPolicy {

    // SPLENDOR_P11_D13: decide() is first-match-wins — the if/else ORDER is
    // the priority. The `priority` numbers are labels only, never compared,
    // and must descend in checked order (100, 90, 60, 50, 10); the old
    // RefreshPerformance 70 contradicted the order (checked AFTER
    // ReigniteFleet 60). See also the starvation interleave in
    // InAppAgentCore.tickSafely: one branch must not monopolise cycles.
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

        // V6 PROMOTION: booster-not-ready now has a SAFE automated recovery
        // (re-ignite fleet) instead of permanent ObserveOnly.
        if (!health.boosterAlive) {
            return AgentDecision(
                action = AgentAction.ReigniteFleet,
                priority = 60,
                reason =
                    "Booster fleet not ready (no fresh adapter heartbeats); " +
                    "re-ignite adapter services."
            )
        }

        if (observation.loadShed == "HEAVY") {
            return AgentDecision(
                action = AgentAction.RefreshPerformance,
                priority = 50,
                reason =
                    "Runtime load-shed is HEAVY; " +
                    "refresh performance telemetry and state."
            )
        }

        // F37-D9: bus-idle / dispatch-stale / gameplay-idle /
        // emergency-rejected used to fall through to a terminal ObserveOnly
        // with no recovery action (400 cycles in the 2026-11-16 session).
        // Route them to the existing self-heal check instead; the P11
        // interleave already escalates repeated self-heal to ReigniteFleet.
        if (!health.busAlive || !health.gameplayAlive || !health.dispatchAlive ||
            health.degradedReasons.contains("emergency-rejected")) {
            return AgentDecision(
                action = AgentAction.RunSelfHealCheck,
                priority = 55,
                reason = "Bus/gameplay/dispatch degraded (${health.degradedReasons}); running self-heal check."
            )
        }

        return AgentDecision(
            action = AgentAction.ObserveOnly,
            priority = 10,
            reason =
                if (health.degradedReasons.isEmpty()) {
                    "Runtime healthy; gameplay contributors own execution; agent supervisory observation only."
                } else {
                    "Runtime degraded without a safe automated recovery action."
                }
        )
    }
}

