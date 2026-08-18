package com.assistant.adapter.smartassist.contributors

import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object DefenseContributor : GameplayContributor {
    override val engineName = "Defense"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.defenderDensity <= 0f) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX,
            targetY = frame.ballY,
            authority = frame.defenderDensity.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
