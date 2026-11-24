package com.assistant.overlay.interceptor

import com.assistant.BallOwnershipEngine
import com.assistant.BallTrajectoryPredictor
import com.assistant.runtime.RuntimeEntity
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * Phase 4, step 1 — ShotIntentProxy (pure Kotlin, no Android, no native).
 *
 * The pose-blocked #8 SUBSTITUTE. Engine #8 (AttackerShotIntentEngine)
 * as originally specified is BLOCKED: TrainedDetectionEngine outputs
 * boxes + class scores only (Ball/Keeper/MyPlayer/OpponentPlayer/
 * Buttons) and NO pose/keypoint/skeleton data exists anywhere in the
 * repo (Adaeze's correction, verified). This proxy produces the
 * DetectionFusionEngine.IntentHint that fuse() already accepts
 * (nullable, never required) from ONLY data that already exists:
 * the ball prediction, the frame entities, and the goal mouth.
 * NO pose data, NO new model, NO new detection.
 *
 * Contract: in = the ball prediction (BallTrajectoryPredictor) +
 * the frame entities (RuntimeFrame) + the goal mouth
 * (GoalMouthGeometry); out = a DetectionFusionEngine.IntentHint.
 * This file builds the proxy ONLY. It does NOT wire itself into
 * OmnipotentGoalkeeperEngine.kt — the wiring piece passes the hint
 * into the fuse() call that today passes null for intent, and this
 * file must not be landed before its inputs exist on main (they do:
 * the full Phase 2 stack, HEAD 8748d909).
 *
 * THE THREE SIGNALS (all three must agree for a strong hint):
 *
 *  (a) Ball at attacker feet — the nearest opponent entity is
 *      within ball-ownership distance of the ball. Threshold:
 *      BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE (60f), REFERENCED
 *      not restated — the repo's own possession rule ("a player
 *      within 60 px of the ball owns it", BallOwnershipEngine.kt:101,
 *      already shared with WingBlockContributor so the literal is
 *      never duplicated). The carrier is that nearest opponent.
 *
 *  (b) Attacker velocity toward goal — the carrier's velocity
 *      points at the mouth. Own goal sits at the BOTTOM of the
 *      portrait frame (same convention as ShotTrajectoryDetector),
 *      so the carrier must move DOWN (velocityYPerSecond > 0) fast
 *      enough to be real motion, and the carrier's velocity,
 *      extrapolated to the goal line, must land inside the mouth
 *      span [goalLeftX, goalRightX]. Magnitude floor:
 *      ATTACKER_MIN_SPEED_PX_PER_SECOND = 40f, DERIVED: the
 *      2 px/frame jitter floor (MotionTracker.kt:34,
 *      DEFLECTION_MIN_SPEED_PX_PER_FRAME) at the ~20 fps processed
 *      rate (Ed's calibrated rate) is 2 * 20 = 40 px/s. A carrier
 *      moving slower than the jitter-equivalent is standing still
 *      for detection purposes and does not count as driving at goal.
 *      No hardcoded direction is used — the mouth geometry decides.
 *
 *  (c) Ball-speed spike — the ball is moving fast enough to be a
 *      struck ball, not a dribble. Threshold:
 *      BALL_SPIKE_SPEED_PX_PER_FRAME = 4f, DERIVED: 2x the jitter
 *      floor (MotionTracker.kt:34, 2f). No higher ball-speed bar
 *      exists anywhere in the repo (grep: the only speed constants
 *      are the 2f floor, restated in BallPathInterceptEngine.kt:95);
 *      4f is a CHOSEN margin, not a measured shot-speed constant:
 *      twice the floor is unambiguously real motion, and a struck
 *      ball separates from a dribbled ball, which travels at the
 *      carrier's running speed. Because the hint needs all three
 *      signals to go strong, a too-low spike bar can only add
 *      corroboration, never a solo trigger.
 *
 * CONFIDENCE MAPPING (by agreeing signals):
 *      3/3 -> 0.85f, isShotIntent = true (STRONG: clears the fusion
 *             0.7 bar with margin).
 *      2/3 -> 0.60f, isShotIntent = false (below the bar — a
 *             dribbling carrier, or a fast ball with no carrier
 *             driving at goal, must never trigger a save alone).
 *      1/3 -> 0.30f, isShotIntent = false.
 *      0/3 -> 0.00f, isShotIntent = false.
 * 0.85, not 1.0, for the full house: this proxy cannot distinguish
 * a shot from a hard pass toward goal — the SAME known limit the
 * trajectory detector states in its own KDoc — and it has no pose
 * data, so it must not claim the trajectory detector's corroborated
 * 1.0. Sub-threshold confidences are audit values (visible in the
 * returned hint and in [IntentEvidence]); fusion only ever acts on
 * the strong one (DetectionFusionEngine rule 1).
 *
 * FAIL OPEN: invalid prediction, invalid mouth, non-finite ball or
 * carrier numbers, no opponent entities, or no carrier within
 * ownership distance -> IntentHint(isShotIntent = false,
 * confidence = 0f). Never throws; evaluateFrame() catches everything
 * and returns NO_INTENT. The trajectory/gauge sources are untouched
 * by this file and unaffected by any proxy fault.
 *
 * PURE: this object keeps no state and has no side effects. The
 * keeper track (id -1) never carries the flag — opponent entities
 * with isGoalkeeper set are excluded from carrier candidacy (a
 * keeper carrying the ball is not a shot setup). The assembler pool
 * caps each side at 11 — a carrier beyond the cap reads as no
 * carrier, same honest limit as the cursor identifier.
 */
object ShotIntentProxy {

    /** A source counts as strong at or above this confidence (mirrors the fusion bar). */
    const val STRONG_CONFIDENCE: Float = 0.85f

    /** Two agreeing signals: moderate, deliberately below the fusion 0.7 bar. */
    const val MODERATE_CONFIDENCE: Float = 0.60f

    /** One agreeing signal: weak, audit value only. */
    const val WEAK_CONFIDENCE: Float = 0.30f

    /**
     * Ball-speed spike floor, px/frame. DERIVED: 2x the jitter floor
     * (MotionTracker.kt:34 DEFLECTION_MIN_SPEED_PX_PER_FRAME = 2f).
     * See the class KDoc — a chosen margin, not a measured constant.
     */
    const val BALL_SPIKE_SPEED_PX_PER_FRAME: Float = 4f

    /**
     * Carrier speed floor toward goal, px/second. DERIVED: the 2
     * px/frame jitter floor at the ~20 fps processed rate
     * (2 * 20 = 40 px/s). See the class KDoc.
     */
    const val ATTACKER_MIN_SPEED_PX_PER_SECOND: Float = 40f

    /**
     * Per-frame audit of the three signals, so the wiring piece (and
     * a live log line) can say WHY the hint fired or did not, instead
     * of trusting one bit. [carrierId] is the nearest opponent's
     * entity id when signal (a) fired, else null.
     */
    data class IntentEvidence(
        val ballAtFeet: Boolean,
        val attackerTowardGoal: Boolean,
        val ballSpeedSpike: Boolean,
        val carrierId: Int? = null,
        val ballSpeedPxPerFrame: Float = 0f
    ) {
        /** Number of agreeing signals (0..3). */
        val agreeing: Int
            get() = (if (ballAtFeet) 1 else 0) +
                (if (attackerTowardGoal) 1 else 0) +
                (if (ballSpeedSpike) 1 else 0)
    }

    val NO_INTENT = DetectionFusionEngine.IntentHint(isShotIntent = false, confidence = 0f)

    /**
     * Pure evaluation: no singleton reads, no mouth resolution — the
     * caller supplies the prediction, the frame, and the mouth, so
     * this is unit-testable without a frame. Never throws.
     */
    fun evaluate(
        prediction: BallTrajectoryPredictor.Prediction,
        frame: RuntimeFrame,
        mouth: GoalMouthGeometry.GoalMouth
    ): DetectionFusionEngine.IntentHint {
        return try {
            val evidence = evaluateEvidence(prediction, frame, mouth)
            hintFromEvidence(evidence)
        } catch (_: Throwable) {
            NO_INTENT
        }
    }

    /** Pure evaluation with the audit detail. Never throws. */
    fun evaluateEvidence(
        prediction: BallTrajectoryPredictor.Prediction,
        frame: RuntimeFrame,
        mouth: GoalMouthGeometry.GoalMouth
    ): IntentEvidence {
        return try {
            evaluateEvidenceUnchecked(prediction, frame, mouth)
        } catch (_: Throwable) {
            IntentEvidence(false, false, false)
        }
    }

    private fun evaluateEvidenceUnchecked(
        prediction: BallTrajectoryPredictor.Prediction,
        frame: RuntimeFrame,
        mouth: GoalMouthGeometry.GoalMouth
    ): IntentEvidence {
        // Fail-open gates: every input must be usable before any
        // signal is tested. The ball position and speed come from
        // the predictor (same source ShotTrajectoryDetector uses);
        // frame.hasBall is deliberately NOT read — it is a
        // possession signal (ours), not ball visibility, and an
        // opponent shot happens exactly when the opponent has it.
        if (!prediction.valid) return IntentEvidence(false, false, false)
        if (!mouth.valid) return IntentEvidence(false, false, false)
        if (!prediction.currentX.isFinite() || !prediction.currentY.isFinite() ||
            !prediction.speed.isFinite()
        ) {
            return IntentEvidence(false, false, false)
        }
        val ballSpeed = prediction.speed

        // Signal (c) first: it needs no carrier. A ball below the
        // spike floor is a dribble or jitter, never a struck ball.
        val ballSpeedSpike = ballSpeed >= BALL_SPIKE_SPEED_PX_PER_FRAME

        // Signal (a): nearest opponent to the ball, within the
        // repo's own ownership distance. Teammates are not
        // attackers; the keeper entity is excluded (a keeper
        // carrying the ball is not a shot setup).
        var carrier: RuntimeEntity? = null
        var carrierDistance = Float.MAX_VALUE
        for (entity in frame.entities) {
            if (entity.teammate) continue
            if (entity.isGoalkeeper) continue
            if (!entity.x.isFinite() || !entity.y.isFinite()) continue
            val distance = hypot(
                (entity.x - prediction.currentX).toDouble(),
                (entity.y - prediction.currentY).toDouble()
            ).toFloat()
            if (distance < carrierDistance) {
                carrierDistance = distance
                carrier = entity
            }
        }
        val ballAtFeet = carrier != null &&
            carrierDistance <= BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE

        // Signal (b): the SAME carrier drives at the mouth. Three
        // sub-gates, all from the mouth geometry, no hardcoded
        // direction: (i) moving down toward the goal line,
        // (ii) fast enough to be real motion (see the derived
        // floor), (iii) the velocity extrapolated to the goal line
        // lands inside the mouth span.
        var attackerTowardGoal = false
        if (ballAtFeet && carrier != null) {
            val vx = carrier.velocityXPerSecond
            val vy = carrier.velocityYPerSecond
            if (vx.isFinite() && vy.isFinite() &&
                vy > 0f &&
                hypot(vx.toDouble(), vy.toDouble()).toFloat() >= ATTACKER_MIN_SPEED_PX_PER_SECOND
            ) {
                // Time (seconds) for the carrier to reach the goal
                // line at the current downward speed. t < 0 means
                // the carrier is already beyond the line.
                val timeToLine = (mouth.goalLineY - carrier.y) / vy
                if (timeToLine.isFinite() && timeToLine >= 0f) {
                    val predictedXAtLine = carrier.x + vx * timeToLine
                    attackerTowardGoal = predictedXAtLine.isFinite() &&
                        predictedXAtLine in mouth.goalLeftX..mouth.goalRightX
                }
            }
        }

        return IntentEvidence(
            ballAtFeet = ballAtFeet,
            attackerTowardGoal = attackerTowardGoal,
            ballSpeedSpike = ballSpeedSpike,
            carrierId = if (ballAtFeet) carrier?.id else null,
            ballSpeedPxPerFrame = ballSpeed
        )
    }

    /**
     * Confidence mapping, by agreeing signals (see the class KDoc):
     * only the full house is strong. isShotIntent is true exactly
     * when all three agree; the sub-threshold confidences are audit
     * values the fusion engine ignores (rule 1 needs >= 0.7).
     */
    fun hintFromEvidence(evidence: IntentEvidence): DetectionFusionEngine.IntentHint {
        return when (evidence.agreeing) {
            3 -> DetectionFusionEngine.IntentHint(isShotIntent = true, confidence = STRONG_CONFIDENCE)
            2 -> DetectionFusionEngine.IntentHint(isShotIntent = false, confidence = MODERATE_CONFIDENCE)
            1 -> DetectionFusionEngine.IntentHint(isShotIntent = false, confidence = WEAK_CONFIDENCE)
            else -> NO_INTENT
        }
    }

    /**
     * Frame convenience: resolves the mouth from the frame
     * (GoalMouthGeometry.resolve — detected box, else calibrated
     * posts, else invalid) and reads the live prediction. Fail
     * open: any failure resolving the mouth or reading the
     * prediction returns NO_INTENT instead of throwing — the keeper
     * engine must never crash on a proxy fault.
     */
    fun evaluateFrame(
        frame: RuntimeFrame,
        captureWidth: Float,
        captureHeight: Float
    ): DetectionFusionEngine.IntentHint {
        return try {
            val mouth = GoalMouthGeometry.resolve(frame, captureWidth, captureHeight)
            val prediction = BallTrajectoryPredictor.current()
            evaluate(prediction, frame, mouth)
        } catch (_: Throwable) {
            NO_INTENT
        }
    }
}
