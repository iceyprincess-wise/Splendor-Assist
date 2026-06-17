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

        if (decision.priority < 130) {
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
