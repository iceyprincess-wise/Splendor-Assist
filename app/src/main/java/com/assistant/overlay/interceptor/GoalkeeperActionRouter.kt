package com.assistant.overlay.interceptor

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

    // No66-D6: the 2D-matrix partition boundaries, named. The TOP/MID
    // split (0.33/0.66) and the BOTTOM dive split (0.4/0.6) were bare
    // literals repeated across arms — drift between them would silently
    // break the partition (the PARRY arm's written 0.33..0.66 range
    // already overstated its effective [0.4, 0.6] range; see below).
    private const val MATRIX_SPLIT_LOW = 0.33f
    private const val MATRIX_SPLIT_HIGH = 0.66f
    private const val BOTTOM_DIVE_LOW = 0.4f
    private const val BOTTOM_DIVE_HIGH = 0.6f

    // No64-D22: last routed action, for per-episode feedback counting.
    // interceptions / crossClaims count episodes (a run of the same
    // action), not frames — the per-frame counts inflated the adaptive
    // bonuses to saturation within seconds of play (trace 2, B3).
    @Volatile
    private var lastRoutedAction: GoalkeeperAction? = null

    // No66-D3: clear the per-episode tracker for a session reset (see
    // GoalkeeperMetricsRegistry.reset()). Without this, the first
    // post-reset PUNCH/CLAIM/RUSH episode could be swallowed when the
    // last pre-reset action was the same.
    fun resetEpisodeTracking() {
        lastRoutedAction = null
    }

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
        keeperBias: KeeperBias,
        // No66-D13: the post/INTERCEPT inputs were computed but never
        // logged, so a live GK_DECISION line could not say why a
        // RUSH_OUT fired or whether post coverage was even consulted.
        // Defaulted so the no64 harness pin (8-arg call) still compiles.
        farPostCoverage: Boolean = false,
        nearPostCoverage: Boolean = false,
        crossIntercept: Boolean = false,
        longBallThreat: Boolean = false
    ): String {
        return "GK_DECISION action=" + action +
            " ShotAnticipationEngine=" + anticipation +
            " PanicSaveEngine=" + panicOverride +
            " OneVsOnePanicEngine=" + panic +
            " OwnGoalAvoidanceEngine=" + safety +
            " CollisionAvoidanceEngine=" + collision +
            " CrossClaimEngine=" + cross +
            " KeeperPositionBiasEngine=" + keeperBias +
            " FarPostCoverageEngine=" + farPostCoverage +
            " NearPostCoverageEngine=" + nearPostCoverage +
            " CrossInterceptionEngine=" + crossIntercept +
            " LongBallCounterEngine=" + longBallThreat
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

        // No66-D5: the registry write stays — RushOutActionEngine and
        // RecoveryPredictionEngine read currentBias outside this call —
        // but the read-back below used to round-trip through the registry
        // for a value this call already holds. Read the local instead.
        GoalkeeperBiasRegistry.currentBias =
            keeperBias

        val activeBias =
            keeperBias

        val farPostCoverage =
            FarPostCoverageEngine.shouldCover(
                decision,
                // L2 (batch 1): the anchored overload — the coverage claim
                // now requires calibrated posts (GoalPostCalibrationEngine
                // .latest(), written every frame by VisionCore).
                com.assistant.GoalPostCalibrationEngine.latest()
            )

        val nearPostCoverage =
            NearPostCoverageEngine.shouldCover(
                decision,
                com.assistant.GoalPostCalibrationEngine.latest()
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
        // complementary, not contradictory. Letter G (G3): the RISKY+SAVE
        // arm that used to read `safety` is deleted below (proven
        // unreachable — the matrix catches every SAVE first), so safety's
        // live effect is the GK_DECISION log line only. A live RISKY
        // routing effect (where a RISKY threat should divert a SAVE) is
        // a tactical decision queued for Ed, not invented here.
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
            // Item 12 (A7): the PUNCH gate used to pre-empt the whole
            // matrix with no height or collision check, while CLAIM
            // needed allowClaim and TOP claims prefer AERIAL (No66-D2).
            // A TOP cross now falls through to the AERIAL arm, and a
            // HIGH-collision cross no longer punches blind.
            if (InterceptionRuntimeRegistry.armed && InterceptionRuntimeRegistry.autoIntercept && cross == CrossAction.PUNCH &&
                decision.heightBand != HeightBand.TOP &&
                CollisionAvoidanceEngine.allowClaim(collision)) {
                GoalkeeperAction.PUNCH_CROSS
            } else if (InterceptionRuntimeRegistry.armed &&
                InterceptionRuntimeRegistry.autoIntercept &&
                cross == CrossAction.CLAIM &&
                // No66-D2: TOP claims take the AERIAL arm below (moved
                // above the matrix), not this gate — the aerial jump
                // reaches higher (0.10h) than the claim (0.35h).
                decision.heightBand != HeightBand.TOP &&
                CollisionAvoidanceEngine.allowClaim(collision)
            ) {
                GoalkeeperAction.CLAIM_CROSS
            } else {
                when {

                    // No66-D1: the saveAttempts increments that used to
                    // sit in these two arms are removed. saveAttempts is
                    // now incremented ONLY on bus-accept in
                    // OmnipotentGoalkeeperEngine (the "SaveOffers" label
                    // in the snapshot); the router increments counted
                    // every DIVE_BOTTOM route whether the offer was
                    // accepted or cooling-dropped, so one accepted
                    // dive-bottom save counted twice and a dropped one
                    // counted once. Behaviour change (counters only):
                    // saveAttempts drops to accepted offers exactly.
                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.BOTTOM && decision.normX < BOTTOM_DIVE_LOW ->
                        GoalkeeperAction.DIVE_BOTTOM_LEFT

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.BOTTOM && decision.normX > BOTTOM_DIVE_HIGH ->
                        GoalkeeperAction.DIVE_BOTTOM_RIGHT

                    // Item 12 (A14): panicSaves used to increment here,
                    // on ROUTE — a cooling-dropped BLOCK still counted.
                    // The increment lives at the accepted-offer site in
                    // OmnipotentGoalkeeperEngine now (same pattern as the
                    // REBOUND_SAVE increment there).
                    anticipation == AnticipationResult.PANIC &&
                        panicOverride &&
                        panic ==
                        PanicAction.BLOCK_LEFT ->
                        GoalkeeperAction.BLOCK_LEFT

                    anticipation == AnticipationResult.PANIC &&
                        panicOverride &&
                        panic ==
                        PanicAction.BLOCK_RIGHT ->
                        GoalkeeperAction.BLOCK_RIGHT

                    panic ==
                        PanicAction.RUSH -> {
                        GoalkeeperAction.RUSH_OUT
                    }

                    // No66-D2 (closes Q65-4): the AERIAL arm used to sit
                    // BELOW the 2D matrix, where it could never fire —
                    // cross==CLAIM needs pri>=100 (NativePorts.claim),
                    // pri>=100 is always SAVE (ShotAnticipationEngine),
                    // and the matrix catches every SAVE, so a TOP cross
                    // always took JUMP_TOP_* even though the CLAIM gate
                    // above had declined it. (The gate is not the binding
                    // constraint no65 named: a CLAIM is always MEDIUM
                    // collision live — BOX zone + pri>=100 — so the
                    // gate's allowClaim never took a CLAIM either; the
                    // matrix was the blocker.) The gate takes the
                    // non-TOP CLAIM cases first (above), so this arm now
                    // fires exactly for SAVE + TOP + CLAIM: AERIAL_CLAIM
                    // (was JUMP_TOP_*). SAVE+MID/BOTTOM CLAIM cases
                    // still take the matrix arms below. INTERCEPT/TRACK
                    // TOP crosses are unchanged (cross is HOLD below
                    // pri 100, so they still take the INTERCEPT arm's
                    // RUSH_OUT below).
                    cross == CrossAction.CLAIM && decision.heightBand == HeightBand.TOP ->
                        GoalkeeperAction.AERIAL_CLAIM

                    // Queue item 13: SAVE-band 1v1 rush — the defensive
                    // layer rushes pre-shot, but a 1v1 that reaches the
                    // shot evaluation without a rush (layer throttled,
                    // attacker closed late) still waited for the shot.
                    // decision.isOneVsOne is PreShotRushEngine.shouldRush
                    // at the evaluate site, so the ball is still with
                    // the attacker. SAVE band only ([100,150)): at PANIC
                    // the panic arm already rushes, and below 100 the
                    // INTERCEPT arm owns RUSH_OUT.
                    decision.isOneVsOne && decision.priority >= 100 && decision.priority < PANIC_PRIORITY_THRESHOLD ->
                        GoalkeeperAction.RUSH_OUT

                    // PRECISE 2D GOALMOUTH COVERAGE MATRIX
                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.TOP && decision.normX < MATRIX_SPLIT_LOW ->
                        GoalkeeperAction.JUMP_TOP_LEFT

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.TOP && decision.normX > MATRIX_SPLIT_HIGH ->
                        GoalkeeperAction.JUMP_TOP_RIGHT

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.TOP ->
                        GoalkeeperAction.JUMP_TOP_CENTER

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.MID && decision.normX < MATRIX_SPLIT_LOW ->
                        GoalkeeperAction.REACH_LEFT_CENTER

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.MID && decision.normX > MATRIX_SPLIT_HIGH ->
                        GoalkeeperAction.REACH_RIGHT_CENTER

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.MID && decision.priority >= 120 ->
                        GoalkeeperAction.REFLEX_CENTER

                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.MID ->
                        GoalkeeperAction.CATCH_CENTER

                    // No66-D6: the written range used to be 0.33..0.66,
                    // but the DIVE_BOTTOM arms above already take
                    // normX < 0.4 and normX > 0.6, so this arm's
                    // effective range was always [0.4, 0.6] — the wider
                    // literal overstated it. Behaviour-neutral: the
                    // literal now states the effective range.
                    anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.BOTTOM && decision.normX in BOTTOM_DIVE_LOW..BOTTOM_DIVE_HIGH ->
                        GoalkeeperAction.PARRY_BOTTOM_CENTER

                    // Letter G (G3): the two post arms that used to sit
                    // here (SAVE + far-post coverage or PROTECT-far bias
                    // -> DIVE_LEFT, SAVE + near-post coverage or
                    // PROTECT-near bias -> DIVE_RIGHT) are DELETED, proven unreachable by
                    // shadowing (No65-D17): for anticipation==SAVE the
                    // 2D-matrix arms above partition every band fully
                    // (TOP, MID, BOTTOM with normX fully partitioned in
                    // each), so a SAVE never reached them. Behaviour-neutral
                    // deletion — they never fired. Reordering post-coverage
                    // above the matrix would CHANGE behaviour (a TOP
                    // far-post SAVE would DIVE_LEFT instead of
                    // JUMP_TOP_LEFT) and stays Ed's tactical call (Q65-5);
                    // the arms return from git history if he approves it.
                    // farPostCoverage/nearPostCoverage/activeBias are still
                    // live inputs to the GK_DECISION log line below, and
                    // activeBias still routes the INTERCEPT arm.

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

                    // Letter G (G3): the RISKY+SAVE arm that used to sit
                    // here (safety==RISKY && anticipation==SAVE ->
                    // BLOCK_LEFT) is DELETED, proven unreachable by the
                    // same shadowing (No66-D7): the matrix catches every
                    // SAVE before it. Behaviour-neutral — it never fired.
                    // BLOCK_LEFT stays reachable via the PANIC arm above.
                    // OwnGoalAvoidanceEngine.evaluate still runs and its
                    // RISKY value is still visible in the GK_DECISION log
                    // line; giving RISKY a live routing effect is queued
                    // for Ed (tactical call, see the safety note above).

                    else ->
                        GoalkeeperAction.TRACK
                }
            }

        // No64-D22: the feedback counters increment only on a NEW episode
        // (the returned action differs from the last routed action).
        // Item 12 (A14): there are no per-route saveAttempts /
        // panicSaves increments left in this file — both counters moved
        // to the accepted-offer site in OmnipotentGoalkeeperEngine.
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
                decisionLogLine(action, anticipation, panicOverride, panic, safety, collision, cross, keeperBias, farPostCoverage, nearPostCoverage, crossIntercept, longBallThreat),
                "GOALKEEPER"
            )
        } catch (_: Throwable) {
            // Logging must never fail routing.
        }

        return action
    }
}
