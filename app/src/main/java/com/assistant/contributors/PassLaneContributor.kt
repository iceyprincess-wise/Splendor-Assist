package com.assistant.contributors

import com.assistant.runtime.*

/*
 * Open-play passing contributor (Task C item (d) onboarding).
 *
 * Uses ONLY real RuntimeFrame data: the lane graph's own viable-lane count,
 * best-lane confidence and pass target produced by the vision pipeline.
 * No fabricated coordinates - if the frame carries no usable pass target,
 * this engine stays silent rather than inventing one.
 */
object PassLaneContributor : GameplayContributor {
    override val engineName = "PassLane"
    override val capabilities = setOf(EngineCapability.PASSING)

    private const val MIN_LANE_CONFIDENCE = 0.25f
    private const val MIN_PASS_DISTANCE_PX = 40f

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.viableLaneCount <= 0) return null
        if (frame.bestLaneConfidence < MIN_LANE_CONFIDENCE) return null
        if (frame.passTargetX < 0f || frame.passTargetY < 0f) return null

        // A pass to our own feet is not a pass.
        val dx = frame.passTargetX - frame.ballX
        val dy = frame.passTargetY - frame.ballY
        if (dx * dx + dy * dy < MIN_PASS_DISTANCE_PX * MIN_PASS_DISTANCE_PX) return null

        val laneAvailability =
            (frame.viableLaneCount.toFloat() / frame.laneCount.coerceAtLeast(1))
                .coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = frame.passTargetX.coerceAtLeast(0f),
            targetY = frame.passTargetY.coerceAtLeast(0f),
            authority = (frame.bestLaneConfidence * (0.6f + 0.4f * laneAvailability))
                .coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
