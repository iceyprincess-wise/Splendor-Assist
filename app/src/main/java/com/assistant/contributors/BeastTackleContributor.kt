package com.assistant.contributors

import com.assistant.BeastTackleEngine
import com.assistant.runtime.*

/*
 * G-N — Beast Tackle contributor (rough play, Ed D11 / §7).
 *
 * Offers a hard tackle (DEFEND, authority 0.90, 20 ms) ONLY when
 * BeastTackleEngine's window is open — which already includes the
 * foul-risk gate (no from-behind, no last-man, no own-box) and the
 * D14 tight-line guard (carrier ahead of the tight line, presser is
 * the designated presser; the line holders never leave). When the
 * window is closed the contributor is silent and the ordinary press
 * owns the frame. A foul that earns a card is a loss, so the gate —
 * not the aggression — is the authority here.
 */
object BeastTackleContributor : GameplayContributor {
    override val engineName = "BeastTackle"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val window = BeastTackleEngine.window(frame)
        if (!window.open) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = 0.90f,
            confidence = frame.confidence,
            durationHintMs = 20L
        )
    }
}
