package com.assistant.contributors

import com.assistant.ClearanceEngine
import com.assistant.runtime.*

/*
 * G-I — Clearance contributor.
 *
 * Under pressure in our own third with the ball: clear it upfield
 * toward the touchline (ClearanceEngine's target). SHOT-class: a
 * clearance is a kick toward a target, and the shoot button is the
 * kick input (requiredControlSlot routes SHOT from slot 2 like any
 * shot). High authority (0.80) — when the clearance window is open,
 * keeping the ball is already lost; the clearance outranks recycling
 * possession. Silent when not under pressure: no hoofing without cause.
 */
object ClearanceContributor : GameplayContributor {
    override val engineName = "Clearance"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.ATTACK)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val decision = ClearanceEngine.decide(frame)
        if (!decision.clear) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            targetX = decision.targetX.coerceIn(0f, frame.captureWidth),
            targetY = decision.targetY.coerceIn(0f, frame.captureHeight),
            authority = 0.80f,
            confidence = frame.confidence,
            durationHintMs = 35L
        )
    }
}
