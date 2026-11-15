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
                // SPLENDOR_P11_D06: verified only on real recovery. The old
                // `|| agentStillAlive` made verified=true whenever the daemon
                // merely kept running — 4,328 consecutive verified=true
                // cycles in the 2026-11-12 log with zero recovery.
                val recoveryImproved =
                    (!before.health.frameAlive && after.health.frameAlive) ||
                    (!before.health.decisionAlive && after.health.decisionAlive)

                ActionVerification(
                    verified = recoveryImproved,
                    detail =
                        if (recoveryImproved)
                            "Runtime health improved after self-heal action."
                        else
                            "Self-heal action produced no verified recovery yet."
                )
            }

            AgentAction.RefreshPerformance -> {
                // SPLENDOR_P11_D06: the old `after.timestampMs >=
                // before.timestampMs` is always true, so verified was a
                // tautology. RefreshPerformance is only selected while
                // load-shed is HEAVY, so verified means the shed cleared.
                val shedCleared = before.loadShed == "HEAVY" && after.loadShed != "HEAVY"
                ActionVerification(
                    verified = shedCleared,
                    detail =
                        if (shedCleared)
                            "Load-shed cleared after performance refresh (HEAVY -> ${after.loadShed})."
                        else
                            "Performance state refresh completed; load-shed still ${after.loadShed} — no verified improvement."
                )
            }

            AgentAction.ReigniteFleet -> {
                // SPLENDOR_P11_D06: the old `|| after.timestampMs >=
                // before.timestampMs` is always true, so verified was a
                // tautology even when nothing was reignited.
                val fleetImproved = !before.health.boosterAlive && after.health.boosterAlive
                ActionVerification(
                    verified = fleetImproved,
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
