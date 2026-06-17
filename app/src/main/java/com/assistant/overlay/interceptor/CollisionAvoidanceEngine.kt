package com.assistant.overlay.interceptor

enum class CollisionRisk {
    LOW,
    MEDIUM,
    HIGH
}

object CollisionAvoidanceEngine {

    fun evaluate(
        decision: ThreatDecision
    ): CollisionRisk {

        return when {

            decision.zone ==
                ThreatZone.GOAL_AREA &&
            decision.priority >= 120 ->
                CollisionRisk.HIGH

            decision.zone ==
                ThreatZone.BOX &&
            decision.priority >= 90 ->
                CollisionRisk.MEDIUM

            else ->
                CollisionRisk.LOW
        }
    }

    fun allowRush(
        risk: CollisionRisk
    ): Boolean {

        return risk !=
            CollisionRisk.HIGH
    }

    fun allowClaim(
        risk: CollisionRisk
    ): Boolean {

        return risk ==
            CollisionRisk.LOW
    }
}
