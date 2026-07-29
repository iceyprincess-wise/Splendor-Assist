package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.CriticalAttackingVectorEngine
import com.assistant.runtime.*

object AttackingVectorContributor : GameplayContributor {
    override val engineName = "AttackingVector"
    override val capabilities = setOf(EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.bestLaneConfidence < 0.35f) return null

        val point = CriticalAttackingVectorEngine.computeAbsoluteScoringVector(
            strikerX = frame.ballX,
            strikerY = frame.ballY,
            gkX = 1620f,
            gkY = 360f,
            goalLeftPostX = 1620f,
            goalLeftPostY = 280f,
            goalRightPostX = 1620f,
            goalRightPostY = 440f
        )

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            targetX = point.x.coerceAtLeast(0f),
            targetY = point.y.coerceAtLeast(0f),
            authority = frame.bestLaneConfidence.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 35L
        )
    }
}
