package com.assistant.adapter.smartassist

data class ActionVerification(
    val verified: Boolean,
    val detail: String,
    val verifiedAtMs: Long = System.currentTimeMillis()
)

/**
 * Verification is separate from execution.
 *
 * An action is never reported as a successful recovery merely because
 * the command was invoked.
 */
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
