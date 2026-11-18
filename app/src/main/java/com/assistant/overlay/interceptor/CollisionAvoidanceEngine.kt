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

    // No66-D2: LOW or MEDIUM (HIGH vetoes) — the no64-D29 rule, already
    // used inline by CrossClaimContributor. allowClaim used to be
    // LOW-only, but a CLAIM is always MEDIUM live (BOX zone +
    // pri>=100), so LOW-only made the router's CLAIM gate provably dead
    // and CLAIM_CROSS unreachable. Same predicate as allowRush above.
    fun allowClaim(
        risk: CollisionRisk
    ): Boolean {

        return risk ==
            CollisionRisk.LOW ||
            risk ==
                CollisionRisk.MEDIUM
    }
}
