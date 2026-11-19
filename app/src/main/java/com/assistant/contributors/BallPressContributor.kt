package com.assistant.contributors

import com.assistant.StaminaEngine
import com.assistant.TackleTimingEngine
import com.assistant.runtime.*

/*
 * Out-of-possession press contributor (Task C item (d) onboarding).
 *
 * When we do not have the ball, the frame's ball position IS the pressing
 * target - no invented coordinates. Deliberately silent while user panic mode
 * is on and deliberately modest in authority (0.35–0.70); the keeper family
 * outranks it only when priority >= 105 (priority/150 >= 0.70).
 */
object BallPressContributor : GameplayContributor {
    override val engineName = "BallPress"
    override val capabilities = setOf(EngineCapability.DEFENSE)

        // SPLENDOR_V43_FAST_GATE
    override fun fastGate(frame: RuntimeFrame): Boolean = frame.trusted && !frame.hasBall && !frame.panic

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.panic) return null // silent while user panic mode is on (frame.panic is the toggle, not a threat panic)
        // Pressing is only meaningful when both sides are actually visible.
        if (frame.playerCount <= 0 || frame.opponentCount <= 0) return null

        val outnumbered = frame.opponentCount > frame.playerCount
        val base = if (outnumbered) 0.45f else 0.35f

        // G-B (batch 4): stamina scales the press — a tired team presses
        // softer (scale in [0.55, 1.0], never silent). G-K (batch 4):
        // an open tackle window turns the press into a tackle at the
        // right moment — authority lifts toward 0.90 and the duration
        // shortens, so the press becomes the tackle instead of a jog.
        val staminaScale = StaminaEngine.pressScale(frame)
        val tackle = TackleTimingEngine.window(frame)
        val authority = if (tackle.open) {
            (0.55f + 0.35f * tackle.quality).coerceIn(0f, 1f)
        } else {
            (base + 0.25f * frame.defenderDensity.coerceIn(0f, 1f)) * staminaScale
        }.coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = if (tackle.open) 20L else 35L
        )
    }
}
