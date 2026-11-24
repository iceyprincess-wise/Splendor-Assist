package com.assistant.overlay.interceptor

import com.assistant.BallTrajectoryPredictor
import com.assistant.runtime.RuntimeFrame

/**
 * Phase 2, engine #1 — ShotTrajectoryDetector (pure Kotlin, no Android, no native).
 *
 * The trajectory trigger (Task 1 of Ed's order). Kills G1 (save
 * evaluation's sole entry is inside `if (anomalyDetected)` at
 * OmnipotentGoalkeeperEngine.kt:396 — a gauge-less shot never reaches
 * the save path) and G2 (a fast shot under a low-scoring gauge pixel
 * scores below SAVE and routes to TRACK while ball speed is not an
 * input anywhere on the dive path).
 *
 * Contract: in = the ball's motion vector (BallTrajectoryPredictor)
 * + the goal mouth (GoalMouthGeometry); out = a ShotHypothesis. A
 * save fires from ball→goal motion with NO gauge pixel: the ball's
 * predicted impact point (6-frame look-ahead) is aimed inside the
 * goal mouth and the ball is moving toward our goal fast enough to
 * be real motion, not detection jitter.
 *
 * This file builds the detector ONLY. It does NOT wire it into
 * OmnipotentGoalkeeperEngine.kt — the scan wiring fix (W) is a
 * separate piece, and this file must not be landed before
 * GoalMouthGeometry (it calls GoalMouthGeometry.resolve()).
 *
 * FAIL OPEN: invalid prediction, no valid goal mouth, ball not
 * moving toward the goal, or speed below the floor -> isShot=false
 * with impact (0f, 0f) — never a guessed impact. A consumer must
 * check isShot before reading impactX/impactY.
 *
 * "Ball leaving attacker feet": NO possession signal is invented.
 * The proxy is the predictor's own velocity/validity — a ball
 * dribbled at the attacker's feet has low, erratic per-frame
 * velocity that fails the floor or the direction gate; a ball
 * struck toward goal carries sustained velocity toward the line.
 * LIMIT, stated honestly: this cannot distinguish a shot from a
 * hard pass toward goal. That disambiguation belongs to the fusion
 * engine (#6, any strong source triggers) and the shot-intent proxy
 * (Phase 4), not to this detector.
 */
object ShotTrajectoryDetector {

    /** Which source produced this hypothesis — the fusion engine keys on it. */
    enum class ShotSource { TRAJECTORY }

    /**
     * A shot hypothesis. [impactX]/[impactY] are the predictor's
     * look-ahead point (capture pixels) and are (0f, 0f) when
     * [isShot] is false. [speed] is the measured per-frame ball
     * speed (px/frame) even on a no-shot, so the fusion engine can
     * see how close the ball came. [goalBound]/[insideMouth] record
     * which aim checks fired, so the fusion engine can audit the
     * decision instead of trusting one bit.
     */
    data class ShotHypothesis(
        val isShot: Boolean,
        val impactX: Float = 0f,
        val impactY: Float = 0f,
        /** Measured ball speed, px/frame (BallTrajectoryPredictor.speed). */
        val speed: Float = 0f,
        /**
         * Corroboration weight, NOT a probability: 1.0 when both aim
         * checks fired, 0.7 when only the deciding check fired, 0.0
         * when not a shot. Per-source reliability weighting belongs
         * to DetectionConfidenceEngine (#7), not here.
         */
        val confidence: Float = 0f,
        val source: ShotSource = ShotSource.TRAJECTORY,
        val goalBound: Boolean = false,
        val insideMouth: Boolean = false
    ) {
        companion object {
            val NO_SHOT = ShotHypothesis(isShot = false)
        }
    }

    // Speed floor: 2 px/frame. SOURCE — MotionTracker.kt:33
    // DEFLECTION_MIN_SPEED_PX_PER_FRAME = 2f: "sub-2 px/frame
    // displacements sit inside detection jitter at the 288x640
    // capture size". MotionTracker's constant is private, so the
    // value is restated here with its source cited, not re-derived.
    // This floor filters JITTER, not slow balls — Ed's standard is
    // that ANY ball speed is a threat, so above the jitter floor
    // there is no higher "shot speed" bar in this detector. At the
    // ~20 fps processed rate 2 px/frame is ~40 px/s.
    const val MIN_SHOT_SPEED_PX_PER_FRAME = 2f

    /** Both aim checks fired. */
    const val CORROBORATED_CONFIDENCE = 1.0f

    /** Only the deciding aim check fired (still a shot). */
    const val SINGLE_CHECK_CONFIDENCE = 0.7f

    /**
     * Pure evaluation: no reads of singletons, no mouth resolution —
     * the caller supplies the prediction and the mouth, so this is
     * unit-testable without a frame.
     *
     * Disagreement rule (isGoalBound vs isInsideMouth), stated
     * exactly — the two checks cover COMPLEMENTARY phases of one
     * flight, not the same test twice:
     * - isInsideMouth fires while the look-ahead point is still
     *   INSIDE the mouth box (approach phase);
     * - isGoalBound fires once the look-ahead point reaches or
     *   passes the goal line inside the span (crossing phase).
     * For a FRAME mouth both tests use the same detected posts and
     * the same line, and they can only both be true AT the line
     * itself — requiring AND would deafen the detector through the
     * whole approach. So for a FRAME mouth EITHER firing is a shot;
     * neither vetoes the other.
     * - For a CALIBRATION mouth the rule is stricter: isInsideMouth
     *   DECIDES. isGoalBound takes goalDetected=false there and
     *   falls back to the 0.32w–0.68w defensive span
     *   (GoalBoundBallEngine GOAL_AREA_LEFT/RIGHT_FRACTION), which
     *   must not override the measured calibrated posts. A
     *   fallback-span-only firing is reported in [goalBound] for
     *   audit but does not make a shot.
     * In both cases the direction gate (velocityY > 0, down the
     * portrait frame toward our goal at the bottom) and the speed
     * floor gate first — a ball moving away from goal never fires
     * even if its look-ahead point sits inside the mouth.
     */
    fun evaluate(
        prediction: BallTrajectoryPredictor.Prediction,
        mouth: GoalMouthGeometry.GoalMouth,
        captureWidth: Float,
        captureHeight: Float
    ): ShotHypothesis {
        // Fail-open gates: every one must pass before any aim test.
        if (!prediction.valid) return ShotHypothesis(isShot = false, speed = prediction.speed)
        if (!mouth.valid) return ShotHypothesis(isShot = false, speed = prediction.speed)
        if (captureWidth <= 0f || captureHeight <= 0f) {
            return ShotHypothesis(isShot = false, speed = prediction.speed)
        }
        if (!prediction.predictedX.isFinite() || !prediction.predictedY.isFinite() ||
            !prediction.velocityY.isFinite() || !prediction.speed.isFinite()
        ) {
            return ShotHypothesis(isShot = false, speed = prediction.speed)
        }
        // Direction gate: our goal sits at the BOTTOM of the portrait
        // frame, so a ball heading for it moves DOWN (vy > 0). A ball
        // moving up the frame is moving away from our goal.
        if (prediction.velocityY <= 0f) return ShotHypothesis(isShot = false, speed = prediction.speed)
        // Speed floor gate: below this the motion is detection
        // jitter, not a ball in flight (see MIN_SHOT_SPEED_PX_PER_FRAME).
        if (prediction.speed < MIN_SHOT_SPEED_PX_PER_FRAME) {
            return ShotHypothesis(isShot = false, speed = prediction.speed)
        }

        val insideMouth = mouth.isInsideMouth(prediction.predictedX, prediction.predictedY)
        // goalDetected=true only for a FRAME mouth, so isGoalBound
        // uses the detected posts there; for CALIBRATION it falls
        // back to its 0.32w–0.68w span (see disagreement rule above).
        // goalLineY is the mouth's line: box bottom (FRAME) or frame
        // bottom (CALIBRATION). NOTE: this is more precise than the
        // live promote call at OmnipotentGoalkeeperEngine.kt:613-618,
        // which passes the frame bottom as the line even when a goal
        // box is detected.
        val goalBound = GoalBoundBallEngine.isGoalBound(
            prediction.predictedX,
            prediction.predictedY,
            prediction.velocityY,
            prediction.valid,
            mouth.goalLeftX,
            mouth.goalRightX,
            mouth.source == GoalMouthGeometry.GoalMouthSource.FRAME,
            mouth.goalLineY,
            captureWidth,
            captureHeight
        )

        val isShot = when (mouth.source) {
            GoalMouthGeometry.GoalMouthSource.FRAME -> insideMouth || goalBound
            // CALIBRATION (and UNAVAILABLE, unreachable — mouth.valid
            // already gated): the calibrated posts decide.
            else -> insideMouth
        }
        if (!isShot) {
            return ShotHypothesis(
                isShot = false,
                speed = prediction.speed,
                goalBound = goalBound,
                insideMouth = insideMouth
            )
        }
        return ShotHypothesis(
            isShot = true,
            impactX = prediction.predictedX,
            impactY = prediction.predictedY,
            speed = prediction.speed,
            confidence = if (insideMouth && goalBound) CORROBORATED_CONFIDENCE
                else SINGLE_CHECK_CONFIDENCE,
            goalBound = goalBound,
            insideMouth = insideMouth
        )
    }

    /**
     * Frame convenience: resolves the mouth from the frame
     * (GoalMouthGeometry.resolve — detected box, else calibrated
     * posts, else invalid) and reads the live prediction. Fail
     * open: any failure resolving the mouth or reading the
     * prediction returns NO_SHOT instead of throwing — the keeper
     * engine must never crash on a detector fault.
     */
    fun evaluateFrame(
        frame: RuntimeFrame,
        captureWidth: Float,
        captureHeight: Float
    ): ShotHypothesis {
        return try {
            val mouth = GoalMouthGeometry.resolve(frame, captureWidth, captureHeight)
            val prediction = BallTrajectoryPredictor.current()
            evaluate(prediction, mouth, captureWidth, captureHeight)
        } catch (_: Throwable) {
            ShotHypothesis.NO_SHOT
        }
    }
}
