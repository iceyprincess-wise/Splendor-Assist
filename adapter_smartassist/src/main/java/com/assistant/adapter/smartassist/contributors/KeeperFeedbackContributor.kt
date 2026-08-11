package com.assistant.adapter.smartassist.contributors

import com.assistant.runtime.*

object KeeperFeedbackContributor : GameplayContributor {
    override val engineName = "KeeperFeedback"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        // Ball must be visible to react to it
        if (frame.ballX <= 0f && frame.ballY <= 0f) return null

        // Use actual goalkeeper position when the detector has found one,
        // otherwise aim at ball position (intercepting path)
        val targetX = if (frame.goalkeeperVisible && frame.goalkeeperX > 0f)
            frame.goalkeeperX else frame.ballX.coerceAtLeast(0f)
        val targetY = if (frame.goalkeeperVisible && frame.goalkeeperY > 0f)
            frame.goalkeeperY else frame.ballY.coerceAtLeast(0f)

        // Authority: scale with how much threat is present — but never require
        // defenderDensity >= 0.5f, which silenced the keeper on clean shots
        val authority = (0.35f + frame.defenderDensity * 0.65f).coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = 28L
        )
    }
}
