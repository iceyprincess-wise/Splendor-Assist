package com.assistant.overlay.interceptor

object LongBallCounterEngine {

    fun detected(
        decision: ThreatDecision
    ): Boolean {

        return decision.direction ==
            ShotDirection.LONG_BALL
    }
}
