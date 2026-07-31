package com.assistant.adapter.smartassist.contributors

import com.assistant.runtime.*

/*
 * Keeper positioning claim.
 *
 * GoalkeeperAdaptiveFeedbackEngine lives in the app module, which this adapter
 * module cannot depend on without inverting the dependency direction. So this
 * contributor derives its claim from RuntimeFrame only -- preserving both
 * module boundaries and engine isolation.
 */
object KeeperFeedbackContributor : GameplayContributor {
    override val engineName = "KeeperFeedback"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.defenderDensity < 0.5f) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = (frame.defenderDensity * 0.8f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 28L
        )
    }
}
