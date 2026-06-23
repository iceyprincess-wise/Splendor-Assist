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


        val bias =
            GoalkeeperBiasRegistry.currentBias


        when (
            RecoveryAuthorityRegistry.lastTarget
        ) {

            RecoveryTarget.LEFT ->
                return RecoveryTarget.LEFT

            RecoveryTarget.RIGHT ->
                return RecoveryTarget.RIGHT

            RecoveryTarget.GOAL_AREA ->
                return RecoveryTarget.GOAL_AREA

            else -> {}
        }

        return when {

            bias ==
                KeeperBias.TIGHTEN_GOAL_AREA ->
                    RecoveryTarget.GOAL_AREA

            bias ==
                KeeperBias.PROTECT_NEAR_POST ->
                    RecoveryTarget.RIGHT

            bias ==
                KeeperBias.PROTECT_FAR_POST ->
                    RecoveryTarget.LEFT


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
