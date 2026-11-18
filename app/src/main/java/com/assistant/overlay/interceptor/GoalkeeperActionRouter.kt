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

    // No64-D22: last routed action, for per-episode feedback counting.
    // interceptions / crossClaims count episodes (a run of the same
    // action), not frames — the per-frame counts inflated the adaptive
    // bonuses to saturation within seconds of play (trace 2, B3).
    @Volatile
    private var lastRoutedAction: GoalkeeperAction? = null

    // No64-D33: pure builder for the GK_DECISION log line (no Android,
    // no I/O) — pinned in the no64 harness. Every field names the engine
    // whose output it carries, so a live session's logs identify each
    // decision-path engine by name.
    fun decisionLogLine(
        action: GoalkeeperAction,
        anticipation: AnticipationResult,
        panicOverride: Boolean,
        panic: PanicAction,
        safety: OwnGoalSafety,
        collision: CollisionRisk,
        cross: CrossAction,
        keeperBias: KeeperBias
    ): String {
        return "GK_DECISION action=" + action +
            " ShotAnticipationEngine=" + anticipation +
            " PanicSaveEngine=" + panicOverride +
            " OneVsOnePanicEngine=" + panic +
            " OwnGoalAvoidanceEngine=" + safety +
            " CollisionAvoidanceEngine=" + collision +
            " CrossClaimEngine=" + cross +
            " KeeperPositionBiasEngine=" + keeperBias
    }

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

        // No64-D19 (lane division, by design — NOT a veto): the contributor
        // drag lane vetoes goal-area cases (OwnGoal BLOCKED) so it does not
        // double-handle them; the save path is the designated lane for
        // goal-area threats, so BLOCKED is intentionally NOT vetoed here —
        // vetoing would make the keeper stand still (TRACK) on point-blank
        // shots, which is strictly worse. The two pipelines are
        // complementary, not contradictory. `safety` is therefore read only
        // by the RISKY+SAVE arm below.
        val safety =
            OwnGoalAvoidanceEngine.evaluate(
                decision.direction,
                decision.zone
            )

        // No64-D22: single exit — every return path flows through the
        // episode-gated counter block at the bottom. The PUNCH/CLAIM gates
        // used to return early (and count per frame); they now only select
        // the action.
        val action: GoalkeeperAction =
            // Interception gate (restored): live read of the autoIntercept setting.
            if (InterceptionRuntimeRegistry.armed && InterceptionRuntimeRegistry.autoIntercept && cross == CrossAction.PUNCH) {
                GoalkeeperAction.PUNCH_CROSS
            } else if (InterceptionRuntimeRegistry.armed &&
                InterceptionRuntimeRegistry.autoIntercept &&
                cross == CrossAction.CLAIM &&
                CollisionAvoidanceEngine.allowClaim(collision)
            ) {
                GoalkeeperAction.CLAIM_CROSS
            } else {
                when {

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.BOTTOM && decision.normX < 0.4f -> {
                        GoalkeeperMetricsRegistry.saveAttempts.incrementAndGet()
                        GoalkeeperAction.DIVE_BOTTOM_LEFT
                    }

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.BOTTOM && decision.normX > 0.6f -> {
                        GoalkeeperMetricsRegistry.saveAttempts.incrementAndGet()
                        GoalkeeperAction.DIVE_BOTTOM_RIGHT
                    }

                    // No64-D24: the PANIC value is now read explicitly.
                    // Behaviour-neutral: panicOverride (pri >= 150) already
                    // implied anticipation == PANIC, and vice versa — the
                    // conjunct only makes the read visible.
                    anticipation == AnticipationResult.PANIC &&
                        panicOverride &&
                        panic ==
                        PanicAction.BLOCK_LEFT -> {
                        GoalkeeperMetricsRegistry
                            .panicSaves
                            .incrementAndGet()

                        GoalkeeperAction.BLOCK_LEFT
                    }

                    anticipation == AnticipationResult.PANIC &&
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

                    // No65-D17: the two post arms below are UNREACHABLE by
                    // shadowing — for anticipation==SAVE every heightBand
                    // is fully covered by the 2D-matrix arms above (TOP →
                    // JUMP_TOP_*, MID → REACH/REFLEX/CATCH, BOTTOM →
                    // DIVE_BOTTOM_*/PARRY, with normX fully partitioned in
                    // each band), so a SAVE never reaches them. The
                    // post-coverage inputs (nearPostCoverage,
                    // farPostCoverage, PROTECT_* bias) are therefore dead
                    // inputs on the SAVE path — same class as the no64
                    // RISKY-arm shadowing. Kept, behaviour-neutral:
                    // reordering post-coverage above the band arms would
                    // CHANGE behaviour (a TOP far-post SAVE would DIVE_LEFT
                    // instead of JUMP_TOP_LEFT — a tactical call for Ed,
                    // queued as Q65-5. Do not delete them silently.
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

        // No64-D22: the feedback counters increment only on a NEW episode
        // (the returned action differs from the last routed action). The
        // saveAttempts / panicSaves increments above are NOT feedback
        // counters and stay per-route, as before.
        if (action != lastRoutedAction) {
            when (action) {
                GoalkeeperAction.PUNCH_CROSS,
                GoalkeeperAction.CLAIM_CROSS ->
                    GoalkeeperMetricsRegistry.crossClaims.incrementAndGet()

                GoalkeeperAction.RUSH_OUT ->
                    GoalkeeperMetricsRegistry.interceptions.incrementAndGet()

                else -> Unit
            }
            lastRoutedAction = action
        }

        // No64-D33: name every decision-path engine and its output in the
        // log, so a live session proves which engines are active. The line
        // is built by the pure decisionLogLine() above; the RuntimeLogger
        // call itself is guarded so a logging failure can never fail
        // routing (HybridExecutionTerminal.route precedent).
        try {
            com.assistant.diagnostic.RuntimeLogger.log(
                decisionLogLine(action, anticipation, panicOverride, panic, safety, collision, cross, keeperBias),
                "GOALKEEPER"
            )
        } catch (_: Throwable) {
            // Logging must never fail routing.
        }

        return action
    }
}
