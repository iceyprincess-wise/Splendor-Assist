package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.MagneticDashAnchor
import com.assistant.runtime.*

object DashAnchorContributor : GameplayContributor {
    override val engineName = "DashAnchor"
    override val capabilities = setOf(EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val result = MagneticDashAnchor.computeAnchorTarget(
            dashX = frame.ballX,
            dashY = frame.ballY,
            directionalX = frame.passTargetX.takeIf { it > 0f } ?: frame.ballX,
            directionalY = frame.passTargetY.takeIf { it > 0f } ?: frame.ballY
        )

        if (result.strength <= 0f) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = result.anchorX,
            targetY = result.anchorY,
            authority = result.strength.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = if (result.turning) 25L else 40L
        )
    }
}
