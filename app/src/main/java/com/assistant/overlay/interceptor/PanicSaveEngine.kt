package com.assistant.overlay.interceptor

object PanicSaveEngine {

    fun shouldPanic(
        decision: ThreatDecision
    ): Boolean {

        // SPLENDOR_P10_SAT_PANIC: PANIC 130 -> 150.
        return decision.priority >= 150
    }
}
