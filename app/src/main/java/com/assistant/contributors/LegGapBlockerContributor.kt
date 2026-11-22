package com.assistant.contributors

import com.assistant.LegGapBlockerEngine
import com.assistant.runtime.*

/*
 * Batch 6b — Leg-Gap Blocker contributor.
 *
 * Drives the controlled defender's body onto the ball->receiver lane
 * (LegGapBlockerEngine) so an opponent's pass meets a body instead of
 * slipping between his legs. DEFEND, authority 0.70, 40 ms (a
 * positioning step, like Marking's 40 ms) — not a barge, not a
 * tackle. Only the controlled defender moves (the teammate nearest
 * the lane point); no second-player control is invented (G-O block
 * stands). When the window is closed the contributor is silent and
 * the ordinary press / marking owns the frame.
 */
object LegGapBlockerContributor : GameplayContributor {
    override val engineName = "LegGapBlocker"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    // Phase L (L-6): LegGapWindow.closedReason had no production reader —
    // the window's diagnostic trail. Log the transition that closes the
    // window (a new rejection reason while closed): one line per reason
    // change at most — never per frame. Same pattern as
    // DecoyRunContributor (item 17 D2-F01) and BodyWeightPressContributor
    // (item 15).
    @Volatile private var lastClosedReason = ""

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val window = LegGapBlockerEngine.window(frame)
        // L-6: transition-gated read of closedReason.
        if (!window.open && window.closedReason != lastClosedReason) {
            try {
                com.assistant.diagnostic.RuntimeLogger.log(
                    "LEG_GAP closed: ${window.closedReason}",
                    "LEG_GAP"
                )
            } catch (_: Throwable) { }
        }
        lastClosedReason = window.closedReason
        if (!window.open) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = window.laneX.coerceIn(0f, frame.captureWidth),
            targetY = window.laneY.coerceIn(0f, frame.captureHeight),
            authority = 0.70f,
            confidence = frame.confidence,
            durationHintMs = 40L,
            targetEntityId = window.defenderId
        )
    }
}
