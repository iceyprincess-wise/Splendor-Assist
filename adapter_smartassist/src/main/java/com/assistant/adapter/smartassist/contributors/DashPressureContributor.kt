package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.OmnipotentDashPressureMatrix
import com.assistant.runtime.*

object DashPressureContributor : GameplayContributor {
    override val engineName = "DashPressure"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        // Our defender position: use goalkeeper when detected, otherwise
        // project back from ball (defender tracks ball with lag).
        // When defX == oppX the engine geometry collapses to a zero vector.
        val defX = if (frame.goalkeeperVisible && frame.goalkeeperX > 0f)
            frame.goalkeeperX else (frame.ballX - 100f).coerceAtLeast(0f)
        val defY = if (frame.goalkeeperVisible && frame.goalkeeperY > 0f)
            frame.goalkeeperY else frame.ballY

        // Opponent ball carrier = ball position (they have the ball)
        val oppX = frame.ballX
        val oppY = frame.ballY

        val packed = OmnipotentDashPressureMatrix.computeHighAuthorityDefensiveVector(
            ballX  = frame.ballX,
            ballY  = frame.ballY,
            defX   = defX,
            defY   = defY,
            defHomeX = 250f,
            defHomeY = 360f,
            oppX   = oppX,
            oppY   = oppY,
            isPlayerHoldingPressure = true
        )

        val tx = OmnipotentDashPressureMatrix.unpackX(packed)
        val ty = OmnipotentDashPressureMatrix.unpackY(packed)
        if (!tx.isFinite() || !ty.isFinite()) return null
        if (tx <= 0f && ty <= 0f) return null

        return EngineContribution(
            engine         = engineName,
            actionClass    = ActionClass.DEFEND,
            targetX        = tx.coerceAtLeast(0f),
            targetY        = ty.coerceAtLeast(0f),
            authority      = frame.defenderDensity.coerceIn(0f, 1f),
            confidence     = frame.confidence,
            durationHintMs = 42L
        )
    }
}
