package com.assistant.adapter.smartassist.contributors

import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object SupportContributor : GameplayContributor {
    override val engineName = "Support"
    override val capabilities = setOf(EngineCapability.SUPPORT, EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = frame.ballX,
            targetY = frame.ballY,
            authority = (frame.bestLaneConfidence * 0.4f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 40L
        )
    }
}
