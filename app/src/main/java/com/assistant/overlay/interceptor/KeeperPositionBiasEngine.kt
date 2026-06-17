package com.assistant.overlay.interceptor

enum class KeeperBias {

    HOLD_CENTER,

    SHADE_LEFT,
    SHADE_RIGHT,

    PROTECT_NEAR_POST,
    PROTECT_FAR_POST,

    TIGHTEN_GOAL_AREA
}

object KeeperPositionBiasEngine {

    fun evaluate(
        decision: ThreatDecision
    ): KeeperBias {

        return when {

            decision.zone == ThreatZone.GOAL_AREA ->
                KeeperBias.TIGHTEN_GOAL_AREA

            decision.direction == ShotDirection.NEAR_POST ->
                KeeperBias.PROTECT_NEAR_POST

            decision.direction == ShotDirection.FAR_POST ->
                KeeperBias.PROTECT_FAR_POST

            decision.zone == ThreatZone.LEFT ->
                KeeperBias.SHADE_LEFT

            decision.zone == ThreatZone.RIGHT ->
                KeeperBias.SHADE_RIGHT

            else ->
                KeeperBias.HOLD_CENTER
        }
    }
}
