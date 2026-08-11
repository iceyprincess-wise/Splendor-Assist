package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.AgilityEngine
import com.assistant.runtime.*

object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val result = AgilityEngine.computeAgility(
            playerVelocity       = frame.bestLaneConfidence * 10f,
            opponentDistance     = (1f - frame.defenderDensity) * 500f,
            movementAngleDegrees = 0f,
            possessionConfidence = frame.confidence,
            turnIntensity        = frame.defenderDensity.coerceIn(0f, 1f)
        )

        // Agility: burst toward open lane under pressure — move toward the best passing lane (open space).
        // Staying at ballX/ballY is a zero-displacement MOVE — the player goes nowhere.
        val targetX = when {
            frame.viableLaneCount > 0 && frame.passTargetX > 0f -> frame.passTargetX
            else -> (frame.ballX + 80f).coerceIn(0f, 1920f)
        }
        val targetY = when {
            frame.viableLaneCount > 0 && frame.passTargetY > 0f -> frame.passTargetY
            else -> frame.ballY
        }

        return EngineContribution(
            engine         = engineName,
            actionClass    = ActionClass.MOVE,
            targetX        = targetX,
            targetY        = targetY,
            authority      = (result.stabilityBoost / 10f).coerceIn(0f, 1f),
            confidence     = frame.confidence,
            durationHintMs = result.shieldDurationMs.coerceIn(15L, 85L)
        )
    }
}
