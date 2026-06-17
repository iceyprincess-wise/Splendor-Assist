
package com.assistant.overlay.interceptor

import com.assistant.overlay.interceptor.GoalkeeperMetricsRegistry


enum class GoalkeeperAction {

    TRACK,

    DIVE_LEFT,
    DIVE_RIGHT,

    CLAIM_CROSS,
    PUNCH_CROSS,

    RUSH_OUT,

    BLOCK_LEFT,
    BLOCK_RIGHT,

    RECOVER,

    HOLD
}

object GoalkeeperActionRouter {

    fun route(
        decision: ThreatDecision
    ): GoalkeeperAction {

        val anticipation =
            ShotAnticipationEngine.evaluate(
                decision
            )

        val panic =
            OneVsOnePanicEngine.evaluate(
                decision
            )

        val cross =
            CrossClaimEngine.evaluate(
                decision
            )

        val collision =
            CollisionAvoidanceEngine.evaluate(
                decision
            )

        val safety =
            OwnGoalAvoidanceEngine.evaluate(
                decision.direction,
                decision.zone
            )

        if (
            !OwnGoalAvoidanceEngine
                .allowExecution(safety)
        ) {
            return GoalkeeperAction.HOLD
        }

        if (
            cross == CrossAction.PUNCH
        ) {
            
GoalkeeperMetricsRegistry
    .crossClaims
    .incrementAndGet()

return GoalkeeperAction.PUNCH_CROSS

        }

        if (
            cross == CrossAction.CLAIM &&
            CollisionAvoidanceEngine
                .allowClaim(collision)
        ) {
            
GoalkeeperMetricsRegistry
    .crossClaims
    .incrementAndGet()

return GoalkeeperAction.CLAIM_CROSS

        }

        return when {

            panic ==
                PanicAction.BLOCK_LEFT -> {
                GoalkeeperMetricsRegistry
                    .panicSaves
                    .incrementAndGet()

                GoalkeeperAction.BLOCK_LEFT
            }

            panic ==
                PanicAction.BLOCK_RIGHT -> {
                GoalkeeperMetricsRegistry
                    .panicSaves
                    .incrementAndGet()

                GoalkeeperAction.BLOCK_RIGHT
            }

            panic ==
                PanicAction.RUSH -> {
                GoalkeeperMetricsRegistry
                    .interceptions
                    .incrementAndGet()

                GoalkeeperAction.RUSH_OUT
            }

            anticipation ==
                AnticipationResult.SAVE &&
            decision.direction ==
                ShotDirection.FAR_POST ->
                    GoalkeeperAction.DIVE_LEFT

            anticipation ==
                AnticipationResult.SAVE &&
            decision.direction ==
                ShotDirection.NEAR_POST ->
                    GoalkeeperAction.DIVE_RIGHT

            anticipation ==
                AnticipationResult.INTERCEPT -> {
                GoalkeeperMetricsRegistry
                    .interceptions
                    .incrementAndGet()

                GoalkeeperAction.RUSH_OUT
            }

            else ->
                GoalkeeperAction.TRACK
        }
    }
}
