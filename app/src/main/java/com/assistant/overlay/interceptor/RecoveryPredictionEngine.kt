package com.assistant.overlay.interceptor

enum class RecoveryTarget {
    CENTER,
    LEFT,
    RIGHT,
    GOAL_AREA
}

object RecoveryPredictionEngine {

    fun predict(
        decision: ThreatDecision
    ): RecoveryTarget {

        return when {

            decision.zone ==
                ThreatZone.GOAL_AREA ->
                    RecoveryTarget.GOAL_AREA

            decision.zone ==
                ThreatZone.LEFT ->
                    RecoveryTarget.LEFT

            decision.zone ==
                ThreatZone.RIGHT ->
                    RecoveryTarget.RIGHT

            else ->
                RecoveryTarget.CENTER
        }
    }
}
