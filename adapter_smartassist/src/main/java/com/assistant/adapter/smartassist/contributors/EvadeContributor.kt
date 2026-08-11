package com.assistant.adapter.smartassist.contributors

import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object EvadeContributor : GameplayContributor {
    override val engineName = "Evade"
    override val capabilities = setOf(EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.defenderDensity < 0.5f) return null

        // Evade toward the best passing lane / open space.
        // Sending the player to ballX/ballY (where they already ARE) is a no-op.
        val targetX = if (frame.viableLaneCount > 0 && frame.passTargetX > 0f)
            frame.passTargetX
        else
            (frame.ballX + 130f).coerceIn(0f, 1920f) // burst forward if no lane

        val targetY = if (frame.viableLaneCount > 0 && frame.passTargetY > 0f)
            frame.passTargetY
        else
            frame.ballY

        return EngineContribution(
            engine          = engineName,
            actionClass     = ActionClass.EVADE,
            targetX         = targetX,
            targetY         = targetY,
            authority       = frame.defenderDensity.coerceIn(0f, 1f),
            confidence      = frame.confidence,
            durationHintMs  = 30L
        )
    }
}
