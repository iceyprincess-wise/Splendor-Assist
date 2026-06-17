package com.assistant.overlay.interceptor

enum class ThreatZone {
    LEFT,
    RIGHT,
    CENTER,
    BOX,
    GOAL_AREA
}

object ThreatZoneEngine {

    fun detect(
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): ThreatZone {

        return when {

            y > (height * 0.85f) ->
                ThreatZone.GOAL_AREA

            y > (height * 0.65f) ->
                ThreatZone.BOX

            x < width / 3 ->
                ThreatZone.LEFT

            x > (width * 2 / 3) ->
                ThreatZone.RIGHT

            else ->
                ThreatZone.CENTER
        }
    }
}
