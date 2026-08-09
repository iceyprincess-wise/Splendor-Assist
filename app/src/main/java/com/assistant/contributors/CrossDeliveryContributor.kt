package com.assistant.contributors

import com.assistant.runtime.*
import kotlin.math.abs
import kotlin.math.hypot

/*
 * Cross-delivery contributor (Task C item (d) - previously deferred, now
 * unblocked by real goal data in the frame).
 *
 * A cross is a pass whose target is in front of the DETECTED goal. This
 * engine therefore requires BOTH real facts at once:
 *  - a viable lane with a real computed target (same source PassLane uses);
 *  - a detected goal frame, with the lane's target actually landing in the
 *    box region in front of it (within 1.5 goal-widths of the goal center).
 *
 * When both hold, it outranks a plain pass (delivering into the box beats
 * recycling possession); when either is missing it stays silent and lets
 * PassLane or ShotContributor own the frame. Distance gate keeps it from
 * double-claiming point-blank situations that are really shots.
 */
object CrossDeliveryContributor : GameplayContributor {
    override val engineName = "CrossDelivery"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.PASSING)

    private const val MIN_LANE_CONFIDENCE = 0.30f
    private const val MIN_GOAL_CONFIDENCE = 0.25f
    private const val BOX_REACH_GOAL_WIDTHS = 1.5f
    private const val MIN_CROSS_DISTANCE_PX = 180f

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.viableLaneCount <= 0) return null
        if (frame.bestLaneConfidence < MIN_LANE_CONFIDENCE) return null
        if (!frame.goalDetected || frame.goalConfidence < MIN_GOAL_CONFIDENCE) return null
        if (frame.goalWidth <= 1f) return null

        // The lane target must land in the box region in front of the goal.
        val reach = frame.goalWidth * BOX_REACH_GOAL_WIDTHS
        val targetToGoal = hypot(
            frame.passTargetX - frame.goalCenterX,
            frame.passTargetY - frame.goalCenterY
        )
        if (targetToGoal > reach) return null

        // Point-blank delivery is a shot's frame, not a cross's.
        val ballTravel = hypot(
            frame.passTargetX - frame.ballX,
            frame.passTargetY - frame.ballY
        )
        if (ballTravel < MIN_CROSS_DISTANCE_PX) return null

        // Delivering into a box where we are not hopelessly outnumbered is
        // worth more; zone balance at the goal side feeds authority.
        val boxCloseness = (1f - targetToGoal / reach).coerceIn(0f, 1f)
        val authority =
            (0.40f + 0.30f * boxCloseness + 0.20f * frame.bestLaneConfidence)
                .coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.CROSS,
            targetX = frame.passTargetX.coerceAtLeast(0f),
            targetY = frame.passTargetY.coerceAtLeast(0f),
            authority = authority,
            confidence = (frame.confidence *
                (0.5f * frame.bestLaneConfidence + 0.5f * frame.goalConfidence))
                .coerceIn(0f, 1f),
            durationHintMs = 50L
        )
    }
}
