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

    // Item 15 (Letter B): lean-break diagnostics. BodyWeightWindow's
    // closedReason + leanFrames had no production reader — the
    // contributor only read .open/.presserId. The fields' documented
    // purpose is "for the log / pins", so the contributor now logs the
    // transition that ends a sustained lean (open -> closed): how many
    // frames the lean held and why it broke. Transition-gated, so one
    // line per lean at most — never per frame.
    @Volatile private var wasOpen = false
    @Volatile private var lastLeanFrames = 0

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val window = BodyWeightPressEngine.window(frame)
        if (!window.open) {
            if (wasOpen) {
                wasOpen = false
                try {
                    com.assistant.diagnostic.RuntimeLogger.log(
                        "BODY_WEIGHT lean broke after ${lastLeanFrames}f: ${window.closedReason}",
                        "BODY_WEIGHT"
                    )
                } catch (_: Throwable) { }
            }
            return null
        }
        wasOpen = true
        lastLeanFrames = window.leanFrames
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
        wasOpen = false
        lastLeanFrames = 0
        BodyWeightPressEngine.reset()
    }
}
