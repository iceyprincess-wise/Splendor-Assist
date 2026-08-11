package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SpeedCompensationEngine
import com.assistant.runtime.*
import kotlin.math.hypot

object SpeedCompensationContributor : GameplayContributor {
    override val engineName = "SpeedCompensation"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null

        // distance: proxy from defenderDensity — high density = close threats
        val distance = ((1f - frame.defenderDensity) * 800f).coerceIn(0f, 1000f)
        // strength: how much pressure — scaled to 0..100
        val strength = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        // angle: derive from ball-to-pass-target vector; 0 when no lane
        val angle = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            val dx = frame.passTargetX - frame.ballX
            val dy = frame.passTargetY - frame.ballY
            Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        } else 0f

        val result = SpeedCompensationEngine.compensate(distance, angle, strength)

        // authority from executionBoost — max is 35.0 in engine
        val authority = (result.executionBoost / 35f).coerceIn(0f, 1f)
        if (authority <= 0f) return null

        // Target: toward pass lane when open, toward ball when defending
        val targetX = if (frame.hasBall && frame.passTargetX > 0f)
            frame.passTargetX else frame.ballX.coerceAtLeast(0f)
        val targetY = if (frame.hasBall && frame.passTargetY > 0f)
            frame.passTargetY else frame.ballY.coerceAtLeast(0f)

        return EngineContribution(
            engine = engineName,
            actionClass = if (frame.hasBall) ActionClass.MOVE else ActionClass.DEFEND,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
