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


        // No66-D10: evaluate the bias for THIS decision directly. The
        // registry read used to return the previous route's bias:
        // predict() runs at Omnipotent:372, before route() at :402 writes
        // the fresh bias, so the recovery target was always one frame
        // stale. KeeperPositionBiasEngine.evaluate is pure (no side
        // effects, no registry reads), so this is a direct swap.
        val bias =
            KeeperPositionBiasEngine.evaluate(decision)



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
