package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.TouchRecoveryEngine
import com.assistant.runtime.*

object TouchRecoveryContributor : GameplayContributor {
    override val engineName = "TouchRecovery"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = TouchRecoveryEngine.recover(pressure, strength)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = frame.ballX,
            targetY = frame.ballY,
            authority = (result.recoveryBoost / 10f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 35L
        )
    }
}
