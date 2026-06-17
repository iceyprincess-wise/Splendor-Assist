package com.assistant.overlay.interceptor

object NearPostCoverageEngine {

    fun shouldCover(
        decision: ThreatDecision
    ): Boolean {

        return decision.direction ==
            ShotDirection.NEAR_POST
    }
}
