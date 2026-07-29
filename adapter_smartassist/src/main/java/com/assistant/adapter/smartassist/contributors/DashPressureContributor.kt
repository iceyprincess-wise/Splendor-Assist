package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.OmnipotentDashPressureMatrix
import com.assistant.runtime.*

object DashPressureContributor : GameplayContributor {
    override val engineName = "DashPressure"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        val packed = OmnipotentDashPressureMatrix.computeHighAuthorityDefensiveVector(
            ballX = frame.ballX,
            ballY = frame.ballY,
            defX = frame.ballX,
            defY = frame.ballY,
            defHomeX = 250f,
            defHomeY = 550f,
            oppX = frame.ballX,
            oppY = frame.ballY,
            isPlayerHoldingPressure = true
        )

        val tx = OmnipotentDashPressureMatrix.unpackX(packed)
        val ty = OmnipotentDashPressureMatrix.unpackY(packed)
        if (!tx.isFinite() || !ty.isFinite()) return null
        if (tx <= 0f && ty <= 0f) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = tx.coerceAtLeast(0f),
            targetY = ty.coerceAtLeast(0f),
            authority = frame.defenderDensity.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 42L
        )
    }
}
