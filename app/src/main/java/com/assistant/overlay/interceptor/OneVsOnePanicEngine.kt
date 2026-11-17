package com.assistant.overlay.interceptor

enum class PanicAction {
    HOLD,
    BLOCK_LEFT,
    BLOCK_RIGHT,
    RUSH
}

object OneVsOnePanicEngine {

    fun evaluate(
        decision: ThreatDecision
    ): PanicAction {

        // SPLENDOR_P10_SAT_PANIC: PANIC 130 -> 150.
        // No64-D9: the threshold is the shared PANIC_PRIORITY_THRESHOLD.
        if (decision.priority < PANIC_PRIORITY_THRESHOLD) {
            return PanicAction.HOLD
        }

        return when (decision.direction) {

            ShotDirection.NEAR_POST ->
                PanicAction.BLOCK_RIGHT

            ShotDirection.FAR_POST ->
                PanicAction.BLOCK_LEFT

            else ->
                PanicAction.RUSH
        }
    }
}
