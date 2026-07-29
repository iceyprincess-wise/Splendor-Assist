package com.assistant.adapter.smartassist.contributors

import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object ShotContributor : GameplayContributor {
    override val engineName = "Shot"
    override val capabilities = setOf(EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.bestLaneConfidence < 0.6f) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            targetX = frame.ballX,
            targetY = frame.ballY,
            authority = frame.bestLaneConfidence.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 35L
        )
    }
}
