package com.assistant.overlay.interceptor

import com.assistant.NativeBridge

enum class CrossAction {
    HOLD,
    CLAIM,
    PUNCH
}

object CrossClaimEngine {
    fun evaluate(decision: ThreatDecision): CrossAction {
        val actionOrdinal = com.assistant.NativePorts.claim(
            decision.direction.ordinal,
            decision.priority
        )
        return CrossAction.values()[actionOrdinal]
    }
}
