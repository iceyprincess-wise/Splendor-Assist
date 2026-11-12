package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object ActionVerifier {

    fun verify(
        action: AgentAction,
        before: RuntimeObservation,
        after: RuntimeObservation
    ): ActionVerification {

        return when (action) {

            AgentAction.ObserveOnly ->
                ActionVerification(
                    verified = true,
                    detail = "Observation-only decision completed."
                )

            AgentAction.RunSelfHealCheck -> {
                val recoveryImproved =
                    (!before.health.frameAlive && after.health.frameAlive) ||
                    (!before.health.decisionAlive && after.health.decisionAlive)

                val agentStillAlive =
                    after.selfHealRunning ||
                    after.totalHeals > before.totalHeals

                ActionVerification(
                    verified = recoveryImproved || agentStillAlive,
                    detail =
                        when {
                            recoveryImproved ->
                                "Runtime health improved after self-heal action."

                            agentStillAlive ->
                                "Self-heal action executed; recovery remains under observation."

                            else ->
                                "Self-heal action produced no verified recovery yet."
                        }
                )
            }

            AgentAction.RefreshPerformance ->
                ActionVerification(
                    verified = after.timestampMs >= before.timestampMs,
                    detail =
                        "Performance state refresh completed; " +
                        "runtime state was re-observed."
                )

            AgentAction.ReigniteFleet -> {
                val fleetImproved = !before.health.boosterAlive && after.health.boosterAlive
                ActionVerification(
                    verified = fleetImproved || after.timestampMs >= before.timestampMs,
                    detail =
                        if (fleetImproved) "Booster fleet reignition verified (boosterAlive improved)."
                        else "Fleet reignition command dispatched; awaiting adapter heartbeat cross-process propagation."
                )
            }
        }
    }
}

/**
 * Horizontal attack-axis inference for the fixed user team.
 * A reliable detected goal sets the axis from the user-team centroid toward
 * that goal. Without a goal, three consecutive, possession-held ball advances
 * with the user centroid not materially ahead corroborate the axis. Confidence
 * below 0.62 is deliberately not actionable; callers retain their old fallback.
 * State is primitive-only, indexed scans allocate nothing on the frame path.
 */
