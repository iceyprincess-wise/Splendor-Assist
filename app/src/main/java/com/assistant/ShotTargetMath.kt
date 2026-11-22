package com.assistant

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure shot-target math for the Q-C3-1 shot-target fix.
 *
 * Pure Kotlin — kotlin.math only, NO Android imports — so the plain-JVM
 * harness can unit-test it without the Android runtime.
 *
 * Coordinate convention (capture space): Y grows DOWNWARD. The goal's left
 * post is the TOP post (goalTopY) and the right post is the BOTTOM post
 * (goalBottomY). The 35/15 insets are Ed-set tactical numbers — do not
 * re-tune them: left = (lpX + 35, lpY + 15), right = (rpX − 35, rpY − 15);
 * both point inward, into the mouth.
 *
 * PICK RULE — MUST stay identical to the native's in
 * native_critical_vector.c (branchless step on the same distance
 * difference, same tie → left, same insets): the radius computed here is
 * the distance to the corner the native picks, so if the two ever disagree
 * the native lands off-target.
 */
data class ShotTarget(val x: Float, val y: Float)

object ShotTargetMath {

    /**
     * The farther inset corner from the keeper; tie → left (deterministic).
     * Keeper near the left post → right corner (far); keeper near the right
     * post → left corner (far); keeper centred → left corner (never the
     * midpoint — a blend would aim at the keeper's body).
     */
    fun farCornerTarget(
        gkX: Float, gkY: Float,
        goalLeftPostX: Float, goalLeftPostY: Float,
        goalRightPostX: Float, goalRightPostY: Float
    ): ShotTarget {
        val gkDistToLeft = hypot(goalLeftPostX - gkX, goalLeftPostY - gkY)
        val gkDistToRight = hypot(goalRightPostX - gkX, goalRightPostY - gkY)
        return if (gkDistToLeft >= gkDistToRight) {
            // Keeper is at least as far from the left post: left is the far
            // corner (tie included — deterministic left).
            ShotTarget(goalLeftPostX + 35f, goalLeftPostY + 15f)
        } else {
            ShotTarget(goalRightPostX - 35f, goalRightPostY - 15f)
        }
    }

    /** Straight-line distance from the striker to the target corner. */
    fun scoringRadius(
        strikerX: Float, strikerY: Float,
        targetX: Float, targetY: Float
    ): Float = hypot(targetX - strikerX, targetY - strikerY)

    /**
     * Pure mirror of the native formula in native_critical_vector.c:
     * angle = atan2(target − origin); r = origin + cos/sin(angle) * radius.
     * With origin = striker and radius = |striker → target| this returns
     * exactly the target corner, by construction.
     */
    fun composeScoringVector(
        originX: Float, originY: Float,
        targetX: Float, targetY: Float,
        radius: Float
    ): ShotTarget {
        val angle = atan2(targetY - originY, targetX - originX)
        return ShotTarget(originX + cos(angle) * radius, originY + sin(angle) * radius)
    }

    /** Real keeper when visible, else the goal centre (fallback). */
    fun keeperPosition(
        goalkeeperVisible: Boolean,
        gkX: Float, gkY: Float,
        goalCenterX: Float, goalCenterY: Float
    ): ShotTarget =
        if (goalkeeperVisible) ShotTarget(gkX, gkY) else ShotTarget(goalCenterX, goalCenterY)
}
