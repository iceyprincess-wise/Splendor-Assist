package com.assistant.overlay.interceptor

import com.assistant.GoalEnd
import com.assistant.GoalPostCalibration
import com.assistant.GoalPostCalibrationEngine
import com.assistant.runtime.RuntimeFrame

/**
 * Phase 2 engine #4 — GoalOrientationResolver (kills G8).
 *
 * Gap G8: block direction is fixed screen-relative with no orientation
 * input anywhere in overlay/interceptor. ShotDirectionEngine.detect()
 * maps LEFT zone -> FAR_POST and RIGHT zone -> NEAR_POST, which is
 * correct ONLY when OUR goal sits at the RIGHT end of the screen. When
 * our goal is at the LEFT end the mapping is mirrored: a shot from the
 * left is at the NEAR post, not the far post — so the keeper shades the
 * wrong post and the block direction is wrong.
 *
 * This engine resolves WHICH end is OUR goal, from two live sources:
 *
 *  1. attackDirection (PRIMARY) — RuntimeFrame.attackDirection, fed by
 *     AttackAxisModel.directionIfConfident() (FrameAssembler.kt:386).
 *     +1 = the user team attacks toward +x (right), -1 = toward -x
 *     (left), 0 = unconfident / unknown. Our goal is the end OPPOSITE
 *     the attack direction: attack +1 -> our goal LEFT; attack -1 ->
 *     our goal RIGHT. AttackAxisModel only reports a direction at
 *     confidence >= 0.62, so a non-zero value is actionable evidence.
 *  2. GoalPostCalibrationEngine.latest() (FALLBACK) — the calibrated
 *     goal box (leftPostX/rightPostX/goalEnd). Used ONLY when the
 *     attack direction is unknown (0). Documented assumption: when the
 *     keeper must act, the goal in view is the goal under attack —
 *     ours. The calibration itself cannot tell whose goal it is; the
 *     fallback is therefore marked OrientationSource.GOAL_BOX_FALLBACK
 *     so callers can tell it apart from the primary source, and it is
 *     never used to OVERRIDE a known attack direction.
 *
 * Fail open: when neither source resolves a side, the result is
 * GoalSide.UNKNOWN and every getter returns its neutral value
 * (Float.NaN for post x, ShotDirection.CENTER for block direction) so
 * callers keep the current screen-relative behaviour. This engine
 * NEVER guesses a side.
 *
 * Pure addition, stateless: resolution reads the live inputs on every
 * call and caches nothing, so there is no stale-orientation risk.
 * No native code, no new dependencies, no logging.
 *
 * Consumers (later Phase 2 engines): ShotTrajectoryDetector and
 * DetectionFusionEngine call resolveFromFrame(frame) once per frame
 * and pass the GoalOrientation to nearPostX / farPostX /
 * blockDirectionFor / correct(). REVIVE-DIVE (Phase 3) uses the same
 * orientation to pick the dive side for a mid-height side shot.
 */
enum class GoalSide {
    LEFT,
    RIGHT,
    UNKNOWN
}

enum class OrientationSource {
    /** Resolved from attackDirection (primary, confident evidence). */
    ATTACK_DIRECTION,
    /** Resolved from the calibrated goal box only (attack direction unknown). */
    GOAL_BOX_FALLBACK,
    /** Neither source resolved a side — fail open. */
    UNRESOLVED
}

data class GoalOrientation(
    val ourGoalSide: GoalSide,
    val source: OrientationSource
)

object GoalOrientationResolver {

    val UNRESOLVED_ORIENTATION = GoalOrientation(GoalSide.UNKNOWN, OrientationSource.UNRESOLVED)

    /**
     * Primary resolution from the attack direction alone.
     * attackDirection: +1 = we attack right -> our goal LEFT;
     * -1 = we attack left -> our goal RIGHT; 0 = unknown.
     */
    fun resolve(attackDirection: Int): GoalOrientation = when {
        attackDirection > 0 -> GoalOrientation(GoalSide.LEFT, OrientationSource.ATTACK_DIRECTION)
        attackDirection < 0 -> GoalOrientation(GoalSide.RIGHT, OrientationSource.ATTACK_DIRECTION)
        else -> UNRESOLVED_ORIENTATION
    }

    /**
     * Full resolution: attack direction first; the calibrated goal box
     * only as fallback when the attack direction is unknown.
     *
     * Cross-check note: when the attack direction IS known, the
     * calibration's goalEnd can only agree with it — the detected goal
     * is either ours (at the end opposite the attack) or the
     * opponent's (at the attack end). The box therefore never overrides
     * the attack direction; it is read only on the 0 path.
     */
    fun resolve(attackDirection: Int, calibration: GoalPostCalibration): GoalOrientation {
        val primary = resolve(attackDirection)
        if (primary.ourGoalSide != GoalSide.UNKNOWN) return primary
        if (!calibration.valid) return UNRESOLVED_ORIENTATION
        return when (calibration.goalEnd) {
            GoalEnd.LEFT -> GoalOrientation(GoalSide.LEFT, OrientationSource.GOAL_BOX_FALLBACK)
            GoalEnd.RIGHT -> GoalOrientation(GoalSide.RIGHT, OrientationSource.GOAL_BOX_FALLBACK)
            GoalEnd.NONE -> UNRESOLVED_ORIENTATION
        }
    }

    /** One-call resolution from a frame: attack direction + live calibration. */
    fun resolveFromFrame(frame: RuntimeFrame): GoalOrientation =
        resolve(frame.attackDirection, GoalPostCalibrationEngine.latest())

    /** One-call resolution from a bare attack direction + live calibration. */
    fun resolveFromLatest(attackDirection: Int): GoalOrientation =
        resolve(attackDirection, GoalPostCalibrationEngine.latest())

    /** Centre x of the calibrated goal mouth, or NaN when uncalibrated. */
    fun goalCentreX(goalBox: GoalPostCalibration): Float =
        if (!goalBox.valid) Float.NaN
        else (goalBox.leftPostX + goalBox.rightPostX) * 0.5f

    /**
     * Near-post x for the resolved side: with our goal at LEFT the near
     * post is the left post; mirrored at RIGHT. NaN when the side is
     * unknown or the box is invalid — callers keep screen-relative
     * behaviour on NaN.
     */
    fun nearPostX(orientation: GoalOrientation, goalBox: GoalPostCalibration): Float {
        if (!goalBox.valid) return Float.NaN
        return when (orientation.ourGoalSide) {
            GoalSide.LEFT -> goalBox.leftPostX
            GoalSide.RIGHT -> goalBox.rightPostX
            GoalSide.UNKNOWN -> Float.NaN
        }
    }

    /** Far-post x: the mirror of nearPostX. NaN when unknown / invalid. */
    fun farPostX(orientation: GoalOrientation, goalBox: GoalPostCalibration): Float {
        if (!goalBox.valid) return Float.NaN
        return when (orientation.ourGoalSide) {
            GoalSide.LEFT -> goalBox.rightPostX
            GoalSide.RIGHT -> goalBox.leftPostX
            GoalSide.UNKNOWN -> Float.NaN
        }
    }

    /**
     * Corrected block direction for a shot crossing at [shotX].
     * The goal mouth is split at its centre: a shot on the half of the
     * mouth nearer the shooter is the NEAR post, the far half is the
     * FAR post. With our goal at RIGHT this reproduces the existing
     * screen-relative mapping (LEFT -> FAR_POST, RIGHT -> NEAR_POST);
     * with our goal at LEFT it is mirrored. UNKNOWN / invalid box /
     * non-finite shotX -> CENTER (no claim; callers keep current
     * behaviour).
     */
    fun blockDirectionFor(
        shotX: Float,
        orientation: GoalOrientation,
        goalBox: GoalPostCalibration
    ): ShotDirection {
        if (!goalBox.valid || !shotX.isFinite()) return ShotDirection.CENTER
        val centre = goalCentreX(goalBox)
        if (!centre.isFinite()) return ShotDirection.CENTER
        val shotOnLeftHalf = shotX < centre
        return when (orientation.ourGoalSide) {
            GoalSide.LEFT -> if (shotOnLeftHalf) ShotDirection.NEAR_POST else ShotDirection.FAR_POST
            GoalSide.RIGHT -> if (shotOnLeftHalf) ShotDirection.FAR_POST else ShotDirection.NEAR_POST
            GoalSide.UNKNOWN -> ShotDirection.CENTER
        }
    }

    /**
     * Correct an already-computed ShotDirection (e.g. from
     * ShotDirectionEngine.detect, whose LEFT -> FAR_POST / RIGHT ->
     * NEAR_POST mapping assumes our goal is at RIGHT). With our goal at
     * LEFT the post labels swap; with our goal at RIGHT or UNKNOWN the
     * direction is returned unchanged. CENTER / CROSS / LONG_BALL are
     * never post labels and pass through untouched.
     */
    fun correct(direction: ShotDirection, orientation: GoalOrientation): ShotDirection {
        if (orientation.ourGoalSide != GoalSide.LEFT) return direction
        return when (direction) {
            ShotDirection.NEAR_POST -> ShotDirection.FAR_POST
            ShotDirection.FAR_POST -> ShotDirection.NEAR_POST
            else -> direction
        }
    }
}
