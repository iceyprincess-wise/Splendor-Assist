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
 * (goalBottomY). The posts are X positions (left/right); the height is a
 * separate Y range (top/bottom) — the two axes are NOT coupled through a
 * diagonal corner: the pick is on the POST axis only, and the returned Y
 * is the caller's aim height (ShotTypeEngine), never the corner's Y.
 * The 35 X-insets are Ed-set tactical numbers — do not re-tune them:
 * left = lpX + 35, right = rpX − 35; both point inward, into the mouth.
 * The old 15 Y-insets are retired by the shot core fix: they ARE the
 * diagonal high corner (left = goalTopY + 15) that sent shots at the bar.
 *
 * PICK RULE — MUST stay identical to the native's in
 * native_critical_vector.c (branchless step on the same distance
 * difference, same tie → left, same X insets): the radius computed here is
 * the distance to the aim point the native also targets (same post, same
 * aimY), so if the two ever disagree the native lands off-target.
 */
data class ShotTarget(val x: Float, val y: Float)

object ShotTargetMath {

    /**
     * The farther post from the keeper, at the caller's aim height;
     * tie → left (deterministic). Keeper near the left post → right
     * post (far); keeper near the right post → left post (far);
     * keeper centred → left post (never the midpoint — a blend would
     * aim at the keeper's body). The pick (keeper distance to the
     * posts, tie → left, 35 X-insets) is unchanged and identical to
     * the native's; only the Y is no longer the diagonal corner's —
     * it is [aimY], the ShotTypeEngine height (POWER/FINESSE low,
     * CHIP high). The default is the low placement, so a caller
     * without a type decision still never aims at the high corner.
     */
    fun farCornerTarget(
        gkX: Float, gkY: Float,
        goalLeftPostX: Float, goalLeftPostY: Float,
        goalRightPostX: Float, goalRightPostY: Float,
        aimY: Float = goalRightPostY - 0.15f * (goalRightPostY - goalLeftPostY)
    ): ShotTarget {
        val gkDistToLeft = hypot(goalLeftPostX - gkX, goalLeftPostY - gkY)
        val gkDistToRight = hypot(goalRightPostX - gkX, goalRightPostY - gkY)
        return if (gkDistToLeft >= gkDistToRight) {
            // Keeper is at least as far from the left post: left is the far
            // post (tie included — deterministic left).
            ShotTarget(goalLeftPostX + 35f, aimY)
        } else {
            ShotTarget(goalRightPostX - 35f, aimY)
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
