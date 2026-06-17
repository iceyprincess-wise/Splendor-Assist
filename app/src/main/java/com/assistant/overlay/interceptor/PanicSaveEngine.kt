package com.assistant.overlay.interceptor

object PanicSaveEngine {

    fun shouldPanic(
        decision: ThreatDecision
    ): Boolean {

        return decision.priority >= 130
    }
}
