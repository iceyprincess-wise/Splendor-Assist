package com.assistant.overlay.interceptor

import com.assistant.GoalPostCalibrationEngine
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * Phase 2, engine #5 — GoalMouthGeometry (pure Kotlin, no Android, no native).
 *
 * Kills G3 (DIVE_LEFT/RIGHT dead — no goal-relative dive targets) and G7
 * (dive-reach refusal not built — OmnipotentGoalkeeperEngine.kt:738
 * documents the refusal: "no dive-reach constant exists in the repo").
 *
 * Contract: in = the detected goal box (RuntimeFrame.goalLeftX /
 * goalRightX / goalTopY / goalBottomY, gated on goalDetected) or, when
 * the frame box is invalid, the calibrated posts from
 * GoalPostCalibrationEngine.latest(); out = the real goal-mouth span in
 * capture pixels + goal-relative dive targets + the dive reach.
 *
 * Own goal sits at the BOTTOM of the portrait frame (same convention as
 * GoalBoundBallEngine): a ball heading for our goal moves DOWN
 * (vyPerSecond > 0) and the goal line is the goal box bottom.
 *
 * FAIL OPEN: when no goal is detected and no valid calibration exists,
 * resolve() returns GoalMouth(valid = false, source = UNAVAILABLE) and
 * diveTargetFor() returns null. There is NO synthetic fallback span in
 * this file — the 0.32w–0.68w goal-area span lives only inside
 * GoalBoundBallEngine as its own fallback, and the blind-SHOT fix
 * removed exactly the pattern of inventing a goal at the screen edge.
 * A caller that receives an invalid mouth or a null dive target must
 * not dive.
 *
 * Consumers (next engines): ShotTrajectoryDetector reads the mouth to
 * test ball→goal motion (impact point inside the mouth); DetectionFusionEngine
 * reads it for impact targets; Phase 3 REVIVE-DIVE reads diveTargetFor()
 * + diveReachPx() to arm DIVE_LEFT/RIGHT with a goal-relative target.
 */
object GoalMouthGeometry {

    /** Where this mouth came from — callers that need height must require FRAME. */
    enum class GoalMouthSource { FRAME, CALIBRATION, UNAVAILABLE }

    /** Which side of the mouth a dive target sits on (from the keeper's view). */
    enum class DiveSide { LEFT, RIGHT, CENTER }

    /**
     * The goal mouth in capture pixels. When [valid] is false every
     * coordinate is 0f and no consumer may use it as a target.
     */
    data class GoalMouth(
        val valid: Boolean,
        val source: GoalMouthSource,
        val goalLeftX: Float = 0f,
        val goalRightX: Float = 0f,
        val goalCenterX: Float = 0f,
        val goalTopY: Float = 0f,
        val goalBottomY: Float = 0f,
        val goalWidthPx: Float = 0f,
        val goalHeightPx: Float = 0f,
        /** Goal line Y: the goal box bottom when the goal is detected, else the frame bottom. */
        val goalLineY: Float = 0f
    ) {
        /**
         * True when the point is inside the mouth. FRAME source: inside the
         * detected box. CALIBRATION source: calibration carries posts only
         * (X, no Y), so the check is X-inside-the-posts AND at/beyond the
         * goal line — the same line test GoalBoundBallEngine.isGoalBound
         * uses (predictedY >= lineY). UNAVAILABLE: always false.
         */
        fun isInsideMouth(x: Float, y: Float): Boolean {
            if (!valid) return false
            if (!x.isFinite() || !y.isFinite()) return false
            if (x < goalLeftX || x > goalRightX) return false
            return when (source) {
                GoalMouthSource.FRAME -> y in goalTopY..goalBottomY
                GoalMouthSource.CALIBRATION -> y >= goalLineY
                GoalMouthSource.UNAVAILABLE -> false
            }
        }

        /**
         * Goal-relative dive target for a predicted impact point
         * (impactX/impactY in capture pixels, e.g. BallTrajectoryPredictor
         * predictedX/predictedY). Returns null when this mouth is invalid —
         * fail open, never a synthetic target. targetX is the impact X
         * clamped to the mouth span; targetY is the impact Y clamped to the
         * mouth's Y span (FRAME) or the goal line (CALIBRATION, height
         * unknown). The DIVE-vs-REACH choice by height band belongs to the
         * Phase 3 REVIVE-DIVE consumer, not to this geometry.
         *
         * [keeperX]/[keeperY] default to the goal centre: the keeper
         * normally stands on the line near centre, so a centre-based reach
         * check is the honest default; pass the real tracked keeper
         * position when it is available for the exact check.
         * [captureWidth]/[captureHeight] feed the reach check via
         * [GoalMouthGeometry.diveReachPx]; when they are 0f (unknown)
         * `reachable` is false — reach unknown, never assumed.
         */
        fun diveTargetFor(
            impactX: Float,
            impactY: Float,
            keeperX: Float = goalCenterX,
            keeperY: Float = goalLineY,
            captureWidth: Float = 0f,
            captureHeight: Float = 0f
        ): DiveTarget? {
            if (!valid) return null
            if (!impactX.isFinite() || !impactY.isFinite()) return null
            val targetX = impactX.coerceIn(goalLeftX, goalRightX)
            val targetY = when (source) {
                GoalMouthSource.FRAME -> impactY.coerceIn(goalTopY, goalBottomY)
                GoalMouthSource.CALIBRATION -> goalLineY
                GoalMouthSource.UNAVAILABLE -> return null
            }
            val side = when {
                targetX < goalCenterX -> DiveSide.LEFT
                targetX > goalCenterX -> DiveSide.RIGHT
                else -> DiveSide.CENTER
            }
            val distancePx = hypot((targetX - keeperX).toDouble(), (targetY - keeperY).toDouble()).toFloat()
            val reachPx = GoalMouthGeometry.diveReachPx(captureWidth, captureHeight)
            return DiveTarget(
                valid = true,
                targetX = targetX,
                targetY = targetY,
                side = side,
                distancePx = distancePx,
                reachable = reachPx > 0f && distancePx <= reachPx
            )
        }
    }

    /** A goal-relative dive target. [UNAVAILABLE] is the fail-open value. */
    data class DiveTarget(
        val valid: Boolean,
        val targetX: Float = 0f,
        val targetY: Float = 0f,
        val side: DiveSide = DiveSide.CENTER,
        /** Distance from the dive origin (keeper, or goal centre) to the target, px. */
        val distancePx: Float = 0f,
        /** True when the target is within [diveReachPx] of the dive origin. */
        val reachable: Boolean = false
    ) {
        companion object {
            val UNAVAILABLE = DiveTarget(valid = false)
        }
    }

    // Dive travel measured from the existing dive vectors (the gesture
    // travel the current dive commands already cover — NOT a measured
    // in-game keeper dive distance):
    // DiveLeftActionEngine.vector():  (0.50w, 0.72h) -> (0.18w, 0.38h)
    // DiveRightActionEngine.vector(): (0.50w, 0.72h) -> (0.82w, 0.38h)
    // so dx = 0.32w, dy = 0.34h, travel = hypot(0.32w, 0.34h).
    // At the live 288x640 portrait capture that is hypot(92.2, 217.6) = 236.3 px.
    // DIVE ends at 0.38h (mid-height); REACH ends at 0.55h.
    // GAME-SIDE REACH IS UNVERIFIED: no measurement exists of how far the
    // in-game keeper actually travels for a dive swipe, and
    // OmnipotentGoalkeeperEngine.kt:738 says the same ("it needs a
    // measured dive-travel distance or Ed's number (queued)"). Until Ed
    // measures it or supplies a number, diveReachPx() is the gesture
    // travel above and every consumer must treat `reachable` as
    // gesture-reach, not game-reach.
    const val DIVE_TRAVEL_DX_FRACTION = 0.32f
    const val DIVE_TRAVEL_DY_FRACTION = 0.34f
    const val DIVE_END_HEIGHT_FRACTION = 0.38f

    /** Gesture travel (px) the existing dive vectors command at this capture size. */
    fun diveReachPx(captureWidth: Float, captureHeight: Float): Float {
        if (captureWidth <= 0f || captureHeight <= 0f) return 0f
        return hypot(
            (DIVE_TRAVEL_DX_FRACTION * captureWidth).toDouble(),
            (DIVE_TRAVEL_DY_FRACTION * captureHeight).toDouble()
        ).toFloat()
    }

    /**
     * Resolve the mouth from a frame. Prefers the frame's detected goal
     * box (gated on goalDetected — RuntimeFrame itself warns its coords
     * may be NON-ZERO when goalDetected=false, so the flag is the gate,
     * never the coordinates). Falls back to the calibrated posts when the
     * frame box is invalid; calibration carries X only, so a CALIBRATION
     * mouth has goalTopY == goalBottomY == goalLineY == captureHeight
     * and goalHeightPx == 0f (height unknown — callers that need height
     * must require FRAME). Returns GoalMouth(valid=false,
     * source=UNAVAILABLE) when neither source is usable.
     */
    fun resolve(frame: RuntimeFrame, captureWidth: Float, captureHeight: Float): GoalMouth {
        if (captureWidth <= 0f || captureHeight <= 0f) {
            return GoalMouth(valid = false, source = GoalMouthSource.UNAVAILABLE)
        }
        if (isSaneBox(
                frame.goalDetected, frame.goalLeftX, frame.goalRightX,
                frame.goalTopY, frame.goalBottomY, captureWidth, captureHeight
            )
        ) {
            val width = frame.goalRightX - frame.goalLeftX
            val height = frame.goalBottomY - frame.goalTopY
            return GoalMouth(
                valid = true,
                source = GoalMouthSource.FRAME,
                goalLeftX = frame.goalLeftX,
                goalRightX = frame.goalRightX,
                goalCenterX = (frame.goalLeftX + frame.goalRightX) * 0.5f,
                goalTopY = frame.goalTopY,
                goalBottomY = frame.goalBottomY,
                goalWidthPx = width,
                goalHeightPx = height,
                goalLineY = frame.goalBottomY
            )
        }
        val calibration = try {
            GoalPostCalibrationEngine.latest()
        } catch (_: Throwable) {
            null
        }
        if (calibration != null && calibration.valid &&
            calibration.rightPostX > calibration.leftPostX &&
            calibration.leftPostX >= 0f && calibration.rightPostX <= captureWidth
        ) {
            val width = calibration.rightPostX - calibration.leftPostX
            return GoalMouth(
                valid = true,
                source = GoalMouthSource.CALIBRATION,
                goalLeftX = calibration.leftPostX,
                goalRightX = calibration.rightPostX,
                goalCenterX = (calibration.leftPostX + calibration.rightPostX) * 0.5f,
                goalTopY = captureHeight,
                goalBottomY = captureHeight,
                goalWidthPx = width,
                goalHeightPx = 0f,
                goalLineY = captureHeight
            )
        }
        return GoalMouth(valid = false, source = GoalMouthSource.UNAVAILABLE)
    }

    /**
     * Primitive-form resolve for callers that hold the box fields without
     * a RuntimeFrame (same sanity rules as [resolve]; no calibration
     * fallback here — pass a frame to [resolve] for that).
     */
    fun fromBox(
        goalDetected: Boolean,
        goalLeftX: Float,
        goalRightX: Float,
        goalTopY: Float,
        goalBottomY: Float,
        captureWidth: Float,
        captureHeight: Float
    ): GoalMouth {
        if (!isSaneBox(
                goalDetected, goalLeftX, goalRightX,
                goalTopY, goalBottomY, captureWidth, captureHeight
            )
        ) {
            return GoalMouth(valid = false, source = GoalMouthSource.UNAVAILABLE)
        }
        return GoalMouth(
            valid = true,
            source = GoalMouthSource.FRAME,
            goalLeftX = goalLeftX,
            goalRightX = goalRightX,
            goalCenterX = (goalLeftX + goalRightX) * 0.5f,
            goalTopY = goalTopY,
            goalBottomY = goalBottomY,
            goalWidthPx = goalRightX - goalLeftX,
            goalHeightPx = goalBottomY - goalTopY,
            goalLineY = goalBottomY
        )
    }

    // Sanity band mirrors GoalPostCalibrationEngine.calibrate(): width
    // within [2%, 60%] of the capture width, posts inside the frame,
    // top < bottom, all finite, gated on the detected flag.
    private fun isSaneBox(
        goalDetected: Boolean,
        goalLeftX: Float,
        goalRightX: Float,
        goalTopY: Float,
        goalBottomY: Float,
        captureWidth: Float,
        captureHeight: Float
    ): Boolean {
        if (!goalDetected) return false
        if (!goalLeftX.isFinite() || !goalRightX.isFinite() ||
            !goalTopY.isFinite() || !goalBottomY.isFinite()
        ) return false
        if (goalRightX <= goalLeftX) return false
        if (goalBottomY <= goalTopY) return false
        val width = goalRightX - goalLeftX
        if (width < captureWidth * 0.02f || width > captureWidth * 0.60f) return false
        if (goalLeftX < 0f || goalRightX > captureWidth) return false
        if (goalTopY < 0f || goalBottomY > captureHeight) return false
        return true
    }
}
