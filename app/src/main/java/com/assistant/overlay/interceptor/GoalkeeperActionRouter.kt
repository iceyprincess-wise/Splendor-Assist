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
    HOLD,
    DIVE_BOTTOM_LEFT,
    DIVE_BOTTOM_RIGHT,
    JUMP_TOP_CENTER,
    JUMP_TOP_LEFT,
    JUMP_TOP_RIGHT,
    REACH_LEFT_CENTER,
    REACH_RIGHT_CENTER,
    CATCH_CENTER,
    REFLEX_CENTER,
    PARRY_BOTTOM_CENTER,
    AERIAL_CLAIM,
    REBOUND_SAVE
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





        // UNCONDITIONAL CROSS EXECUTION: Removed InterceptionRuntimeRegistry.autoIntercept gates
        if (cross == CrossAction.PUNCH) {
            GoalkeeperMetricsRegistry
                .crossClaims
                .incrementAndGet()

            return GoalkeeperAction.PUNCH_CROSS
        }

        if (cross == CrossAction.CLAIM &&
            CollisionAvoidanceEngine.allowClaim(collision)
        ) {
            GoalkeeperMetricsRegistry
                .crossClaims
                .incrementAndGet()

            return GoalkeeperAction.CLAIM_CROSS
        }

        return when {

            panicOverride && decision.heightBand == HeightBand.BOTTOM && decision.normX < 0.4f -> {
                GoalkeeperMetricsRegistry.panicSaves.incrementAndGet()
                GoalkeeperAction.DIVE_BOTTOM_LEFT
            }

            panicOverride && decision.heightBand == HeightBand.BOTTOM && decision.normX > 0.6f -> {
                GoalkeeperMetricsRegistry.panicSaves.incrementAndGet()
                GoalkeeperAction.DIVE_BOTTOM_RIGHT
            }

            panicOverride && decision.heightBand == HeightBand.BOTTOM && decision.normX in 0.33f..0.66f -> {
                GoalkeeperMetricsRegistry.panicSaves.incrementAndGet()
                GoalkeeperAction.PARRY_BOTTOM_CENTER
            }

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

            // PRECISE 2D GOALMOUTH COVERAGE MATRIX
            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.TOP && decision.normX < 0.33f ->
                GoalkeeperAction.JUMP_TOP_LEFT

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.TOP && decision.normX > 0.66f ->
                GoalkeeperAction.JUMP_TOP_RIGHT

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.TOP ->
                GoalkeeperAction.JUMP_TOP_CENTER

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.MID && decision.normX < 0.33f ->
                GoalkeeperAction.REACH_LEFT_CENTER

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.MID && decision.normX > 0.66f ->
                GoalkeeperAction.REACH_RIGHT_CENTER

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.MID && decision.priority >= 120 ->
                GoalkeeperAction.REFLEX_CENTER

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.MID ->
                GoalkeeperAction.CATCH_CENTER

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.BOTTOM && decision.normX in 0.33f..0.66f ->
                GoalkeeperAction.PARRY_BOTTOM_CENTER

            // AERIAL COVERAGE
            cross == CrossAction.CLAIM && decision.heightBand == HeightBand.TOP ->
                GoalkeeperAction.AERIAL_CLAIM

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

            safety == OwnGoalSafety.RISKY &&
                anticipation == AnticipationResult.SAVE -> {
                GoalkeeperMetricsRegistry
                    .panicSaves
                    .incrementAndGet()

                GoalkeeperAction.BLOCK_LEFT
            }

            else ->
                GoalkeeperAction.TRACK
        }
    }
}
