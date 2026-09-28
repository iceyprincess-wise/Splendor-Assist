package com.assistant.overlay.interceptor

import com.assistant.NativeBridge

object CrossClaimEngine {
    fun evaluate(decision: ThreatDecision): CrossAction {
        val actionOrdinal = NativeBridge.nativeCrossClaimEvaluate(
            decision.direction.ordinal,
            decision.priority
        )
        return CrossAction.values()[actionOrdinal]
    }
}
