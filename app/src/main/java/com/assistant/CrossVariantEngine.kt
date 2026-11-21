package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-H — Cross-Variant Engine (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G9): every cross was the same — no
 * low/driven/early/floated selection. Builds on D8 (the frame already
 * distinguishes the four cross TYPES — pass/shot/head/opponent-punch);
 * this selects the VARIANT within a cross delivery: how the ball is
 * delivered, from crosser position + box occupancy + near/far post.
 *
 * Selection (pure, from the frame's real goal box + entities):
 *  - LOW: box crowded (>= 4 opponents near the goal) — drill it low to
 *    the near post, under the crowd.
 *  - DRIVEN: box moderately occupied + a shooting lane viable — flat,
 *    fast ball to the goal centre (short duration hint).
 *  - EARLY: ball still in the middle third + a runner ahead — cross
 *    early, ahead of the receiver, into the runner's path.
 *  - FLOATED: box sparse (<= 2 opponents near the goal) — float it to
 *    the far post, high, for the header.
 * The variant moves the target INSIDE the box region only (never
 * outside the goal span by more than the D8 reach) and adjusts the
 * duration hint. CrossDeliveryContributor applies it.
 */
data class CrossVariantDecision(
    val targetX: Float,
    val targetY: Float,
    val durationHintMs: Long
)

object CrossVariantEngine {

    const val CROWDED_BOX_MIN_OPPONENTS = 4
    const val SPARSE_BOX_MAX_OPPONENTS = 2
    /** Box region: within this of the goal centre counts as "in the box". */
    const val BOX_RADIUS_FRACTION_OF_WIDTH = 0.30f
    /** Early cross: ball behind this line (own/middle third by attack). */
    const val EARLY_LINE_FRACTION_OF_WIDTH = 0.45f

    fun select(
        frame: RuntimeFrame,
        baseTargetX: Float,
        baseTargetY: Float,
        shootingViable: Boolean
    ): CrossVariantDecision? {
        if (!frame.trusted || !frame.hasBall || !frame.goalDetected) return null
        if (frame.goalWidth <= 1f) return null
        val w = frame.captureWidth
        val goalCx = frame.goalCenterX
        val goalCy = frame.goalCenterY
        val boxRadius = BOX_RADIUS_FRACTION_OF_WIDTH * w
        val boxOpponents = frame.entities.count { e ->
            !e.teammate && hypot(e.x - goalCx, e.y - goalCy) <= boxRadius
        }
        val nearPostY = if (frame.ballY <= goalCy) frame.goalTopY else frame.goalBottomY
        val farPostY = if (frame.ballY <= goalCy) frame.goalBottomY else frame.goalTopY
        // Early: ball still behind the early line (measured from our end).
        val attackSign = if (frame.attackDirection < 0) -1f else 1f
        val progress = (frame.ballX - if (attackSign > 0) 0f else w) * attackSign / w
        val runnerAhead = frame.entities.any { e ->
            e.teammate && (e.x - frame.ballX) * attackSign > 0f &&
                hypot(e.x - frame.ballX, e.y - frame.ballY) > w * 0.10f
        }
        return when {
            boxOpponents >= CROWDED_BOX_MIN_OPPONENTS -> CrossVariantDecision(
                targetX = goalCx,
                targetY = (nearPostY + (goalCy - nearPostY) * 0.15f)
                    .coerceIn(frame.goalTopY, frame.goalBottomY),
                durationHintMs = 45L
            )
            shootingViable -> CrossVariantDecision(
                targetX = goalCx,
                targetY = goalCy,
                durationHintMs = 35L
            )
            progress < EARLY_LINE_FRACTION_OF_WIDTH && runnerAhead -> CrossVariantDecision(
                targetX = (baseTargetX + attackSign * w * 0.05f).coerceIn(0f, w),
                targetY = baseTargetY.coerceIn(0f, frame.captureHeight),
                durationHintMs = 50L
            )
            boxOpponents <= SPARSE_BOX_MAX_OPPONENTS -> CrossVariantDecision(
                targetX = goalCx,
                // Floated: far post, close to the far-post edge of the
                // mouth for the header (0.15 in from the post).
                targetY = (farPostY + (goalCy - farPostY) * 0.15f)
                    .coerceIn(frame.goalTopY, frame.goalBottomY),
                durationHintMs = 55L
            )
            else -> CrossVariantDecision(
                targetX = baseTargetX,
                targetY = baseTargetY,
                durationHintMs = 50L
            )
        }
    }
}
