package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.MagneticDashAnchor
import com.assistant.runtime.*

object DashAnchorContributor : GameplayContributor {
    override val engineName = "DashAnchor"
    override val capabilities = setOf(EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        // Resolve high-speed target vector with fallback guarantees
        val directionalX = when {
            frame.passTargetX > 0f -> frame.passTargetX
            frame.goalDetected && frame.goalRightX > 0f ->
                (frame.goalRightX + frame.goalLeftX) * 0.5f
            else -> frame.ballX + 200f
        }
        val directionalY = when {
            frame.passTargetY > 0f -> frame.passTargetY
            frame.goalDetected && frame.goalTopY > 0f ->
                (frame.goalTopY + frame.goalBottomY) * 0.5f
            else -> frame.ballY
        }

        val result = MagneticDashAnchor.computeAnchorTarget(
            dashX = frame.ballX,
            dashY = frame.ballY,
            directionalX = directionalX,
            directionalY = directionalY
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
