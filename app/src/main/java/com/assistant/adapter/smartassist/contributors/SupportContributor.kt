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
        // off-ball support run: fire when a TEAMMATE has the ball, not us
        if (!frame.trusted || frame.hasBall) return null
        if (frame.viableLaneCount <= 0) return null
        val targetX = frame.passTargetX.coerceAtLeast(0f)
        val targetY = frame.passTargetY.coerceAtLeast(0f)
        if (targetX <= 0f && targetY <= 0f) return null

        // Authority: blend lane confidence with share of lanes that are viable
        val laneShare = frame.viableLaneCount.toFloat() /
                        frame.laneCount.toFloat().coerceAtLeast(1f)
        val authority = (frame.bestLaneConfidence * 0.65f + laneShare * 0.35f)
                        .coerceIn(0f, 1f)

        return EngineContribution(
            engine          = engineName,
            actionClass     = ActionClass.MOVE,
            targetX         = targetX,
            targetY         = targetY,
            authority       = authority,
            confidence      = frame.confidence,
            durationHintMs  = 40L
        )
    }
}
