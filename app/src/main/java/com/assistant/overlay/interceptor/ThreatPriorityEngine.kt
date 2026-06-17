package com.assistant.overlay.interceptor

data class ThreatDecision(
    val threat: ThreatType,
    val zone: ThreatZone,
    val direction: ShotDirection,
    val priority: Int
)

object ThreatPriorityEngine {

    fun evaluate(
        threat: ThreatType,
        zone: ThreatZone
    ): ThreatDecision {

        val direction =
            ShotDirectionEngine.detect(
                zone,
                threat
            )

        var priority = threat.score

        when (zone) {

            ThreatZone.GOAL_AREA ->
                priority += 40

            ThreatZone.BOX ->
                priority += 25

            ThreatZone.CENTER ->
                priority += 10

            else -> {}
        }

        return ThreatDecision(
            threat = threat,
            zone = zone,
            direction = direction,
            priority = priority
        )
    }
}
