package com.assistant.overlay.interceptor

object CrossInterceptionEngine {

    fun shouldIntercept(
        decision: ThreatDecision
    ): Boolean {
        // Patch 4: a random coin-flip used to drop ~0.1% of genuine
        // crosses. A cross threat is a cross threat: no random veto.
        return decision.direction == ShotDirection.CROSS
    }
}
