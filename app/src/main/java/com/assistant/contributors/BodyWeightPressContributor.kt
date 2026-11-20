package com.assistant.contributors

import com.assistant.BodyWeightPressEngine
import com.assistant.runtime.*

/*
 * Batch 6b — Body-Weight Press contributor.
 *
 * Holds a sustained lean/shoulder against a shielding carrier
 * (BodyWeightPressEngine): the offer opens only after 3 consecutive
 * frames of the same presser in contact, and is re-offered every
 * frame while contact holds — that re-offer IS the sustained state,
 * unlike Beast's one-shot 20 ms tackle. DEFEND, authority 0.80
 * (below Beast's 0.90 — a lean is not a tackle), 85 ms duration
 * hint (the most RuntimeDecisionLoop honours). The foul-risk gates
 * and the D14 tight-line guard come from BeastTackleEngine's window,
 * reused whole — a lean never opens where a tackle would be a card.
 * Only the controlled defender (the presser) moves; no
 * second-player control is invented (G-O block stands). When the
 * window is closed the contributor is silent and the ordinary press
 * / Beast owns the frame.
 */
object BodyWeightPressContributor : GameplayContributor {
    override val engineName = "BodyWeightPress"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val window = BodyWeightPressEngine.window(frame)
        if (!window.open) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = 0.80f,
            confidence = frame.confidence,
            durationHintMs = BodyWeightPressEngine.LEAN_DURATION_HINT_MS,
            targetEntityId = window.presserId
        )
    }

    override fun reset() {
        BodyWeightPressEngine.reset()
    }
}
