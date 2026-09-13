package com.assistant.overlay.interceptor

enum class HeightBand { TOP, MID, BOTTOM }

data class ThreatDecision(
    val threat: ThreatType,
    val zone: ThreatZone,
    val direction: ShotDirection,
    val priority: Int,
    val heightBand: HeightBand = HeightBand.MID,
    val normX: Float = 0.5f
)

object ThreatPriorityEngine {

    fun evaluate(
        threat: ThreatType,
        zone: ThreatZone,
        x: Int = 0,
        y: Int = 0,
        width: Int = 1,
        height: Int = 1
    ): ThreatDecision {

        val direction =
            ShotDirectionEngine.detect(
                zone,
                threat
            )

        var priority = threat.score

        // INTERCEPTION AUTHORITY BOOST
        when (direction) {

            ShotDirection.CROSS ->
                priority += 35

            ShotDirection.LONG_BALL ->
                priority += 30

            else -> {}
        }

        priority +=
            (InterceptionRuntimeRegistry.awareness / 2)

        priority +=
            (InterceptionRuntimeRegistry.prediction / 2)

        priority +=
            GoalkeeperAdaptiveFeedbackEngine
                .interceptionBonus()

        priority +=
            GoalkeeperAdaptiveFeedbackEngine
                .recoveryBonus()

        when (zone) {

            ThreatZone.GOAL_AREA ->
                priority += 40

            ThreatZone.BOX ->
                priority += 25

            ThreatZone.CENTER ->
                priority += 10

            else -> {}
        }

        val ny = if (height > 0) y.toFloat() / height.toFloat() else 0.5f
        val nx = if (width > 0) x.toFloat() / width.toFloat() else 0.5f
        val band = when {
            ny > 0.80f -> HeightBand.BOTTOM
            ny < 0.40f -> HeightBand.TOP
            else -> HeightBand.MID
        }

        return ThreatDecision(
            threat = threat,
            zone = zone,
            direction = direction,
            priority = priority,
            heightBand = band,
            normX = nx
        )
    }
}
