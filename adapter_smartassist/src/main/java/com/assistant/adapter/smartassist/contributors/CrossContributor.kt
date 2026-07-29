package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.CrossPrecisionEngine
import com.assistant.runtime.*

object CrossContributor : GameplayContributor {
    override val engineName = "Cross"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.PASSING)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.viableLaneCount <= 0) return null

        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = CrossPrecisionEngine.calculate(
            frame.passTargetX,
            frame.passTargetY,
            strength
        )

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.CROSS,
            targetX = result.crossX.coerceAtLeast(0f),
            targetY = result.crossY.coerceAtLeast(0f),
            authority = result.confidence.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
