package com.assistant.adapter.smartassist.contributors

import com.assistant.admin.AdminConfigStore
import com.assistant.adapter.smartassist.MagneticFeetEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

/*
 * REPAIRED (Task C, field-log proven): touchRetention/10 routinely hit
 * 1.0, and with confidence 1.0 this stabilizer outweighed every real
 * action in arbitration (6574 wins in the 18:38 session). MagneticFeet is
 * ball-control SUPPORT - it should steady possession between actions, not
 * outvote a shot. Authority is now capped, admin-tunable live:
 *   assist.contrib.magneticfeet.cap (default 0.35)
 */
object MagneticFeetContributor : GameplayContributor {
    override val engineName = "MagneticFeet"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = MagneticFeetEngine.stabilize(pressure, strength)
        val cap = try {
            AdminConfigStore.get("assist.contrib.magneticfeet.cap", 0.65f)
        } catch (_: Throwable) { 0.65f }
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = frame.ballX,
            targetY = frame.ballY,
            authority = (result.touchRetention / 10f).coerceIn(0f, cap),
            confidence = frame.confidence,
            durationHintMs = 40L
        )
    }
}
