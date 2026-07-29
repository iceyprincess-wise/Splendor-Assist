package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.WingBlockEngine
import com.assistant.runtime.*

object WingBlockContributor : GameplayContributor {
    override val engineName = "WingBlock"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.opponentCount <= 0) return null

        val result = WingBlockEngine.calculateWingBlockVector(
            wingerX = frame.ballX,
            wingerY = frame.ballY,
            wingerVx = 0f,
            wingerVy = 0f,
            pitchWidth = 1650f
        ) ?: return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = result.targetX.coerceAtLeast(0f),
            targetY = result.targetY.coerceAtLeast(0f),
            authority = frame.defenderDensity.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
