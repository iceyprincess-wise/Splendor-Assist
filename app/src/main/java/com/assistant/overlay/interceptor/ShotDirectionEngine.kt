package com.assistant.overlay.interceptor

enum class ShotDirection {
    NEAR_POST,
    FAR_POST,
    CENTER,
    CROSS,
    LONG_BALL
}

object ShotDirectionEngine {

    fun detect(
        zone: ThreatZone,
        threat: ThreatType
    ): ShotDirection {

        return when {

            zone == ThreatZone.LEFT &&
            threat.score >= 85 ->
                ShotDirection.FAR_POST

            zone == ThreatZone.RIGHT &&
            threat.score >= 85 ->
                ShotDirection.NEAR_POST

            zone == ThreatZone.BOX &&
            threat.score >= 75 ->
                ShotDirection.CROSS

            zone == ThreatZone.GOAL_AREA &&
            threat.score >= 75 ->
                ShotDirection.CENTER

            else ->
                ShotDirection.LONG_BALL
        }
    }
}
