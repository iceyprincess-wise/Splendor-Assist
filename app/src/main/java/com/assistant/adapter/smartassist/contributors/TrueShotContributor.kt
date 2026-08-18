package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.TrueShotEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

/**
 * TrueShotContributor
 *
 * TRUE on-target shot: aims the open post opposite the goalkeeper.
 * Distance gate 700px — wider than ShotContributor (550px).
 * No confidence threshold gating it; distance is the only requirement.
 * Gets +0.18 authority boost when SmartAssist corrections are active,
 * ensuring it beats ShotContributor in arbitration and overrides SA drift.
 */
object TrueShotContributor : GameplayContributor {
    override val engineName = "TrueShot"
    override val capabilities = setOf(EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val result = TrueShotEngine.compute(
            frame.ballX, frame.ballY,
            frame.goalLeftX, frame.goalRightX,
            frame.goalTopY, frame.goalBottomY,
            frame.goalkeeperX, frame.goalkeeperVisible,
            frame.defenderDensity,
            frame.goalDetected
        ) ?: return null
        val boost = if (frame.enabled) 0.18f else 0f
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            targetX = result.targetX,
            targetY = result.targetY,
            authority = (result.authority + boost).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
