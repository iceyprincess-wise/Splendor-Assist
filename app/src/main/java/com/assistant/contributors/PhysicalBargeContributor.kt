package com.assistant.contributors

import com.assistant.PhysicalBargeEngine
import com.assistant.runtime.*

/*
 * G-M — Physical Barge contributor.
 *
 * When the barge window is open (presser inside contact distance of
 * a carrier-paced ball, approach not from behind), offer a DEFEND
 * contribution with barge=true toward the carrier. The barge flag
 * (EngineContribution.barge) is the Q68-31 channel: RuntimeDecisionLoop
 * .chooseRequest renders it as a short, hard swipe (duration clamped
 * to <= 20 ms) instead of an ordinary DEFEND drag. Authority 0.75 —
 * a barge is a committed physical challenge, not a jog. Silent when
 * the window is closed: no barge without contact range, no barge from
 * behind (that is a foul, and the foul-risk gate in BeastTackleEngine
 * owns that judgement).
 */
object PhysicalBargeContributor : GameplayContributor {
    override val engineName = "PhysicalBarge"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val window = PhysicalBargeEngine.window(frame)
        if (!window.open) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = 0.75f,
            confidence = frame.confidence,
            durationHintMs = 20L,
            barge = true
        )
    }
}
