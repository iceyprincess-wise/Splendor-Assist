package com.assistant.overlay.interceptor

object PanicSaveEngine {

    fun shouldPanic(
        decision: ThreatDecision
    ): Boolean {

        // SPLENDOR_P10_SAT_PANIC: PANIC 130 -> 150.
        // No64-D9: the threshold is the shared PANIC_PRIORITY_THRESHOLD.
        return decision.priority >= PANIC_PRIORITY_THRESHOLD
    }
}
