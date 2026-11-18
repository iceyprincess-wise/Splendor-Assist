package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object CrossContributor : GameplayContributor {
    override val engineName = "Cross"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.PASSING)

    // No67-D28: a cross needs the ball (as CrossDelivery requires); without
    // the gate this fired on defending frames and could win via the ConAI
    // fallback. Minimum lane confidence matches CrossDelivery (0.30).
    override fun fastGate(frame: RuntimeFrame): Boolean = frame.trusted && frame.hasBall && frame.viableLaneCount > 0

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.viableLaneCount <= 0) return null
        if (frame.bestLaneConfidence < 0.30f) return null

        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = CrossPrecisionEngine.calculate(
            frame.passTargetX,
            frame.passTargetY,
            strength
        )

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.CROSS,
            targetX = result.crossX.coerceAtLeast(0f),
            targetY = result.crossY.coerceAtLeast(0f),
            authority = result.confidence.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}

/* ========
DashAnchorContributor
======== */
