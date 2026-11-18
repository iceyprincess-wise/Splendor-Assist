package com.assistant.contributors

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

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = (base + 0.25f * frame.defenderDensity.coerceIn(0f, 1f))
                .coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 35L
        )
    }
}
