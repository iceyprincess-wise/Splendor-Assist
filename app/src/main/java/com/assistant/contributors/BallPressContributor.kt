package com.assistant.contributors

import com.assistant.runtime.*

/*
 * Out-of-possession press contributor (Task C item (d) onboarding).
 *
 * When we do not have the ball, the frame's ball position IS the pressing
 * target - no invented coordinates. Deliberately silent during panic frames
 * (PanicSaveContributor owns those) and deliberately modest in authority so
 * the keeper family always outranks it inside the box.
 */
object BallPressContributor : GameplayContributor {
    override val engineName = "BallPress"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.panic) return null // panic family owns this frame
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
