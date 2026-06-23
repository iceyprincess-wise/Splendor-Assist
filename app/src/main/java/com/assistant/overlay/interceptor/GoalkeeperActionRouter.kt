
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


        val panicOverride =
            PanicSaveEngine.shouldPanic(
                decision
            )

        val keeperBias =
            KeeperPositionBiasEngine.evaluate(
                decision
            )


        GoalkeeperBiasRegistry.currentBias =
            keeperBias

        val activeBias =
            GoalkeeperBiasRegistry.currentBias

        val farPostCoverage =
            FarPostCoverageEngine.shouldCover(
                decision
            )

        val nearPostCoverage =
            NearPostCoverageEngine.shouldCover(
                decision
            )

        val crossIntercept =
            CrossInterceptionEngine.shouldIntercept(
                decision
            )

        val longBallThreat =
            LongBallCounterEngine.detected(
                decision
            )


        if (
            !InterceptionRuntimeRegistry.enabled
        ) {
            return GoalkeeperAction.TRACK
        }

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
            InterceptionRuntimeRegistry.autoIntercept.not() &&
            cross == CrossAction.PUNCH
        ) {
            
GoalkeeperMetricsRegistry
    .crossClaims
    .incrementAndGet()

return GoalkeeperAction.PUNCH_CROSS

        }

        if (
            InterceptionRuntimeRegistry.autoIntercept.not() &&
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

            panicOverride &&
                panic ==
                PanicAction.BLOCK_LEFT -> {
                GoalkeeperMetricsRegistry
                    .panicSaves
                    .incrementAndGet()

                GoalkeeperAction.BLOCK_LEFT
            }

            panicOverride &&
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
            (
                farPostCoverage ||
                activeBias ==
                    KeeperBias.PROTECT_FAR_POST
            ) ->
                    GoalkeeperAction.DIVE_LEFT

            anticipation ==
                AnticipationResult.SAVE &&
            (
                nearPostCoverage ||
                activeBias ==
                    KeeperBias.PROTECT_NEAR_POST
            ) ->
                    GoalkeeperAction.DIVE_RIGHT

            anticipation ==
                AnticipationResult.INTERCEPT &&
            (
                crossIntercept ||
                longBallThreat ||
                activeBias ==
                    KeeperBias.SHADE_LEFT ||
                activeBias ==
                    KeeperBias.SHADE_RIGHT
            ) -> {
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
