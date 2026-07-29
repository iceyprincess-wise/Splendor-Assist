package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.MagneticFeetEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object MagneticFeetContributor : GameplayContributor {
    override val engineName = "MagneticFeet"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = MagneticFeetEngine.stabilize(pressure, strength)
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = frame.ballX,
            targetY = frame.ballY,
            authority = (result.touchRetention / 10f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 40L
        )
    }
}
