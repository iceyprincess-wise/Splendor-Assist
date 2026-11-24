package com.assistant.overlay.interceptor

/**
 * Phase 2, engine #6 — DetectionFusionEngine (pure Kotlin, no Android, no native).
 *
 * The CORE of the detection stack. Kills G1 (a gauge-less shot never
 * reaches the save path) and G9 (the hardcoded gauge ratios are the
 * only shot signal) by fusing every shot signal into ONE shot
 * hypothesis: gauge OR ball-trajectory OR attacker-intent. No single
 * missed signal may equal "no save".
 *
 * FUSION RULES (implemented exactly as stated here):
 *
 *  1. ANY single strong source triggers. A source is STRONG when:
 *     - TRAJECTORY: trajectory != null && trajectory.isShot &&
 *       trajectory.confidence >= STRONG_THRESHOLD (0.7), or
 *     - GAUGE: gauge != null && gauge.found &&
 *       gauge.confidence >= STRONG_THRESHOLD (0.7), or
 *     - INTENT: intent != null && intent.isShotIntent &&
 *       intent.confidence >= STRONG_THRESHOLD (0.7).
 *     One strong source is enough; isShot is true.
 *
 *  2. Two (or three) agreeing sources raise confidence. The fused
 *     confidence is the MAXIMUM of the strong sources' confidences
 *     plus a corroboration bonus of +0.1 per ADDITIONAL strong source
 *     beyond the first, capped at 1.0:
 *       confidence = min(1.0, maxStrong + 0.1 * (strongCount - 1)).
 *     A single strong source keeps its own confidence, unboosted.
 *
 *  3. Impact point. The trajectory impact wins when the trajectory
 *     is a strong source (it is a measured look-ahead point). When
 *     the trajectory is not strong and the gauge is, the impact is
 *     the gauge hit position PROJECTED onto the mouth: x clamped
 *     into [goalLeftX, goalRightX], y clamped into the mouth's Y
 *     span (FRAME) or onto the goal line (CALIBRATION). When only
 *     INTENT is strong there is no positional evidence at all, so
 *     the impact is (0f, 0f) — the consumer must save without an
 *     aim point (a rush, not an aimed dive) and must NOT read
 *     (0f, 0f) as a dive target.
 *
 *  4. No source strong -> isShot = false, impact (0f, 0f), sources
 *     empty. NEVER a guessed impact.
 *
 *  5. Invalid mouth -> trajectory-only decision. When !mouth.valid
 *     the gauge position cannot be confirmed goal-bound and cannot
 *     be projected, and the intent signal has no goal geometry to
 *     anchor to, so ONLY a strong trajectory source may trigger;
 *     gauge and intent are recorded nowhere and contribute nothing.
 *     (Consequence, stated honestly: a gauge-only shot with no
 *     detected or calibrated goal mouth does NOT trigger a save
 *     under this rule. If that is ever judged too strict, the
 *     change belongs in DetectionConfidenceEngine (#7) or the scan
 *     wiring (W), not in a silent exception here.)
 *
 *  6. Orientation is accepted but never gates the decision. A shot
 *     is a shot whichever end of the screen our goal sits at — the
 *     keeper still saves. The orientation is part of this call so
 *     the per-frame call site is a single call shared with the
 *     dive-side decision; the Phase 3 REVIVE-DIVE consumer reads
 *     the orientation separately to pick the dive side. This engine
 *     returns no orientation-corrected output.
 *
 *  7. Fail open throughout: any null input, non-finite number, or
 *     unexpected fault returns NO_SHOT instead of throwing. The
 *     keeper engine must never crash on a fusion fault.
 *
 * This file is fusion logic ONLY. It does NOT wire itself into
 * OmnipotentGoalkeeperEngine.kt — the scan wiring fix (W) is a
 * separate piece that calls fuse() unconditionally every frame and
 * replaces the gauge-only branch at OmnipotentGoalkeeperEngine.kt:396.
 * It does NOT duplicate any dependency: the trajectory hypothesis
 * comes from ShotTrajectoryDetector, the gauge hit from
 * AdaptiveGaugeClassifier, the mouth from GoalMouthGeometry, the
 * orientation from GoalOrientationResolver.
 *
 * KNOWN LIMIT, stated honestly: AdaptiveGaugeClassifier.GaugeHit
 * confidence is MODEL EVIDENCE (0.5 on the seed model, rising to
 * 1.0 after 32 confirmed learned hits), not per-pixel match
 * strength — that is GaugeHit.score (0.5..1.0). Rule 1 above keys
 * on confidence exactly as specified, so on a fresh seed model a
 * gauge hit alone scores 0.5 and does NOT reach the 0.7 bar until
 * the model has learned (13 confirmed hits reach 0.7). Per-source
 * reliability weighting belongs to DetectionConfidenceEngine (#7);
 * if #7 decides the gauge's per-pixel score should count toward
 * the bar, that change belongs there, not in a second threshold
 * invented here.
 */
object DetectionFusionEngine {

    /** Which source produced (part of) the fused hypothesis. */
    enum class ShotSource { TRAJECTORY, GAUGE, INTENT }

    /**
     * One fused shot hypothesis. [impactX]/[impactY] are capture
     * pixels and are (0f, 0f) when [isShot] is false (rule 4) or
     * when only INTENT is strong (rule 3 — no positional evidence).
     * A consumer must check isShot before reading the impact, and
     * must treat (0f, 0f) with INTENT in [sources] as "save, no aim
     * point". [sources] lists ONLY the sources that actually
     * triggered (rule 5: on an invalid mouth a strong gauge/intent
     * is not listed, because it contributed nothing).
     */
    data class FusedShotHypothesis(
        val isShot: Boolean,
        val impactX: Float = 0f,
        val impactY: Float = 0f,
        val confidence: Float = 0f,
        val sources: Set<ShotSource> = emptySet()
    ) {
        companion object {
            val NO_SHOT = FusedShotHypothesis(isShot = false)
        }
    }

    /**
     * Attacker shot-intent hint — PLACEHOLDER CONTRACT for Phase 4.
     *
     * Engine #8 (AttackerShotIntentEngine) does not exist yet: it is
     * BLOCKED on pose data (no pose/keypoint output exists anywhere
     * in the detector today). Until it lands, callers pass null for
     * [intent] and the fusion runs on trajectory + gauge only.
     * When #8 lands it should either reuse this type as-is or
     * replace it with its own richer hint and update this file's
     * rule 1 to read the new fields — the nullable slot in fuse()
     * is the seam, and intent is NEVER required (rule 1: any single
     * strong source triggers, intent included, intent never needed).
     */
    data class IntentHint(
        val isShotIntent: Boolean,
        val confidence: Float = 0f
    )

    /** A source counts as strong at or above this confidence. */
    const val STRONG_THRESHOLD: Float = 0.7f

    /** Corroboration bonus per additional strong source beyond the first. */
    const val CORROBORATION_BONUS: Float = 0.1f

    /**
     * Fuse the per-frame shot signals into one hypothesis.
     *
     * [trajectory] is ShotTrajectoryDetector.evaluate()'s output
     * (null when the detector did not run this frame);
     * [gauge] is AdaptiveGaugeClassifier.scan()'s output (null when
     * the gauge scan did not run); [intent] is the Phase 4
     * AttackerShotIntentEngine hint (null until #8 exists);
     * [mouth] is GoalMouthGeometry.resolve()'s output;
     * [orientation] is GoalOrientationResolver.resolveFromFrame()'s
     * output (accepted, never gates — rule 6).
     *
     * Never throws; any fault returns NO_SHOT.
     */
    fun fuse(
        trajectory: ShotTrajectoryDetector.ShotHypothesis?,
        gauge: AdaptiveGaugeClassifier.GaugeHit?,
        intent: IntentHint?,
        mouth: GoalMouthGeometry.GoalMouth,
        orientation: GoalOrientation
    ): FusedShotHypothesis {
        return try {
            fuseUnchecked(trajectory, gauge, intent, mouth, orientation)
        } catch (_: Throwable) {
            FusedShotHypothesis.NO_SHOT
        }
    }

    private fun fuseUnchecked(
        trajectory: ShotTrajectoryDetector.ShotHypothesis?,
        gauge: AdaptiveGaugeClassifier.GaugeHit?,
        intent: IntentHint?,
        mouth: GoalMouthGeometry.GoalMouth,
        orientation: GoalOrientation
    ): FusedShotHypothesis {
        // Orientation is accepted by contract and never gates the
        // decision (rule 6). Referenced here so the parameter is
        // part of the decision record, not dead: an UNKNOWN side
        // changes nothing, a known side changes nothing either —
        // the shot decision is orientation-independent by design.
        @Suppress("UNUSED_VARIABLE")
        val unusedOrientation = orientation

        val mouthValid = mouth.valid

        // Rule 1: strong sources. Non-finite confidences count as 0
        // (never strong, never corrupt the max below).
        val trajectoryStrong = trajectory != null && trajectory.isShot &&
            trajectory.confidence.isFinite() && trajectory.confidence >= STRONG_THRESHOLD
        val gaugeStrong = mouthValid && gauge != null && gauge.found &&
            gauge.confidence.isFinite() && gauge.confidence >= STRONG_THRESHOLD
        val intentStrong = mouthValid && intent != null && intent.isShotIntent &&
            intent.confidence.isFinite() && intent.confidence >= STRONG_THRESHOLD

        // Rule 5: invalid mouth -> trajectory-only decision. The
        // mouthValid conjuncts above already exclude gauge/intent;
        // trajectoryStrong has no mouth conjunct because the
        // trajectory detector itself gates on a valid mouth
        // (ShotTrajectoryDetector.evaluate returns NO_SHOT for an
        // invalid mouth), so a strong trajectory already implies a
        // mouth was valid when it was evaluated.
        val strongCount = (if (trajectoryStrong) 1 else 0) +
            (if (gaugeStrong) 1 else 0) +
            (if (intentStrong) 1 else 0)
        if (strongCount == 0) return FusedShotHypothesis.NO_SHOT

        // Rule 2: max strong confidence + corroboration bonus, capped.
        var maxConfidence = 0f
        if (trajectoryStrong) maxConfidence = maxOf(maxConfidence, trajectory.confidence)
        if (gaugeStrong) maxConfidence = maxOf(maxConfidence, gauge.confidence)
        if (intentStrong) maxConfidence = maxOf(maxConfidence, intent.confidence)
        val confidence = (maxConfidence + CORROBORATION_BONUS * (strongCount - 1))
            .coerceIn(0f, 1f)

        val sources = buildSet {
            if (trajectoryStrong) add(ShotSource.TRAJECTORY)
            if (gaugeStrong) add(ShotSource.GAUGE)
            if (intentStrong) add(ShotSource.INTENT)
        }

        // Rule 3: impact point. Trajectory wins when strong; else
        // the gauge hit projected onto the mouth; else (intent
        // only) no positional evidence -> (0f, 0f).
        var impactX = 0f
        var impactY = 0f
        if (trajectoryStrong) {
            val tx = trajectory!!.impactX
            val ty = trajectory.impactY
            if (tx.isFinite() && ty.isFinite()) {
                impactX = tx
                impactY = ty
            }
            // A strong trajectory with a non-finite impact is a
            // detector fault: keep the shot (the source was strong)
            // but report no aim point rather than a NaN target.
        } else if (gaugeStrong) {
            impactX = gauge!!.x.toFloat().coerceIn(mouth.goalLeftX, mouth.goalRightX)
            impactY = when (mouth.source) {
                GoalMouthGeometry.GoalMouthSource.FRAME ->
                    gauge.y.toFloat().coerceIn(mouth.goalTopY, mouth.goalBottomY)
                // CALIBRATION carries posts only (no height): the
                // goal line is the only honest Y.
                else -> mouth.goalLineY
            }
        }

        return FusedShotHypothesis(
            isShot = true,
            impactX = impactX,
            impactY = impactY,
            confidence = confidence,
            sources = sources
        )
    }
}
