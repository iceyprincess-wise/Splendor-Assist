package com.assistant.overlay.interceptor

object FarPostCoverageEngine {

    fun shouldCover(
        decision: ThreatDecision
    ): Boolean {

        return decision.direction ==
            ShotDirection.FAR_POST
    }
}
