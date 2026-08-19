package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.MagneticDashAnchor
import com.assistant.runtime.*

object DashAnchorContributor : GameplayContributor {
    override val engineName = "DashAnchor"
    override val capabilities = setOf(EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        // When a real passing lane exists, aim the anchor toward it.
        // When there is no lane (high pressure, closed space) the anchor
        // must still fire — use goal position when detected, otherwise
        // project forward 200px from ball. Without this fallback the
        // contributor silently returned null every high-pressure frame
        // (directionalX == dashX → velocity 0 → strength 0 → null).
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
