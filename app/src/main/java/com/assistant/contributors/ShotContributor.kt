package com.assistant.contributors

import com.assistant.runtime.*
import kotlin.math.hypot

/*
 * Shot contributor (Task C item (d) - previously deferred, now unblocked).
 *
 * This engine was deliberately NOT onboarded until the frame carried the
 * goal detector's real output. It aims only at coordinates the vision
 * pipeline actually produced:
 *
 *  - fires only when the goal frame is DETECTED this frame (real box, real
 *    confidence), we have the ball, and the ball is within shooting range
 *    of the goal it can see;
 *  - aim point is INSIDE the detected goal mouth, biased toward the half
 *    away from the goalkeeper when the keeper is visible, straight at the
 *    center when not;
 *  - silent otherwise. No goal in view = no shot. Ever.
 */
object ShotContributor : GameplayContributor {
    override val engineName = "Shot"
    override val capabilities = setOf(EngineCapability.ATTACK)

    private const val MIN_GOAL_CONFIDENCE = 0.30f
    private const val MAX_SHOT_RANGE_PX = 620f
    private const val MOUTH_INSET = 0.18f // keep the aim inside the posts

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (frame.confidence < 0.05f) return null
        val possessionWeight = if (frame.hasBall) 1.0f else 0.0f // Shots require possession
        val trustWeight = if (frame.trusted) 1.0f else 0.5f
        val fluidMultiplier = possessionWeight * trustWeight
        if (!frame.goalDetected) return null
        if (frame.goalConfidence < MIN_GOAL_CONFIDENCE) return null
        if (frame.goalWidth <= 1f) return null

        val range = hypot(
            frame.goalCenterX - frame.ballX,
            frame.goalCenterY - frame.ballY
        )
        if (range > MAX_SHOT_RANGE_PX) return null

        // Aim inside the mouth, away from the keeper if we can see one.
        val inset = frame.goalWidth * MOUTH_INSET
        val leftAim = frame.goalLeftX + inset
        val rightAim = frame.goalRightX - inset
        val aimX = when {
            !frame.goalkeeperVisible -> frame.goalCenterX
            frame.goalkeeperX <= frame.goalCenterX -> rightAim
            else -> leftAim
        }
        val aimY = (frame.goalTopY + (frame.goalBottomY - frame.goalTopY) * 0.5f)

        // Closer shot + clearer goal sighting = more authority. A keeper we
        // can actually see (and aim around) is worth more than a blind shot.
        val proximity = (1f - range / MAX_SHOT_RANGE_PX).coerceIn(0f, 1f)
        val keeperBonus = if (frame.goalkeeperVisible) 0.15f else 0f
        val authority = fluidMultiplier *
            (0.35f + 0.35f * proximity + 0.15f * frame.goalConfidence + keeperBonus)
                .coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            targetX = aimX.coerceAtLeast(0f),
            targetY = aimY.coerceAtLeast(0f),
            authority = authority,
            confidence = (frame.confidence * frame.goalConfidence).coerceIn(0f, 1f),
            durationHintMs = 30L
        )
    }
}
