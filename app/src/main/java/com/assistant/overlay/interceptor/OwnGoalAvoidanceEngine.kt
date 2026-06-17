package com.assistant.overlay.interceptor

enum class OwnGoalSafety {

    SAFE,

    RISKY,

    BLOCKED
}

object OwnGoalAvoidanceEngine {

    fun evaluate(
        direction: ShotDirection,
        zone: ThreatZone
    ): OwnGoalSafety {

        return when {

            zone == ThreatZone.GOAL_AREA &&
            direction == ShotDirection.CENTER ->
                OwnGoalSafety.BLOCKED

            zone == ThreatZone.GOAL_AREA ->
                OwnGoalSafety.RISKY

            else ->
                OwnGoalSafety.SAFE
        }
    }

    fun allowExecution(
        safety: OwnGoalSafety
    ): Boolean {

        return safety !=
            OwnGoalSafety.BLOCKED
    }
}
