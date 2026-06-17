package com.assistant.overlay.interceptor

object GoalkeeperDecisionRegistry {

    @Volatile
    var latestDecision: ThreatDecision? = null
}
