package com.assistant.overlay.interceptor

enum class CrossAction {
    HOLD,
    CLAIM,
    PUNCH
}

object CrossClaimEngine {

    fun evaluate(
        decision: ThreatDecision
    ): CrossAction {

        if (
            decision.direction !=
            ShotDirection.CROSS
        ) {
            return CrossAction.HOLD
        }

        return when {

            decision.priority >= 140 ->
                CrossAction.PUNCH

            decision.priority >= 100 ->
                CrossAction.CLAIM

            else ->
                CrossAction.HOLD
        }
    }
}
