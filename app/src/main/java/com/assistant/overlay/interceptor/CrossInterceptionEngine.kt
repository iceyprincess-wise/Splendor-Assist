package com.assistant.overlay.interceptor

object CrossInterceptionEngine {

    fun shouldIntercept(
        decision: ThreatDecision
    ): Boolean {

        return decision.direction ==
            ShotDirection.CROSS
    }
}
