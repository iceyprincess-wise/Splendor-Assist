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

        // No65-D25: direction-first — a post direction wins over the zone.
        // The old GOAL_AREA-first order shadowed PROTECT_* for any
        // directly-constructed GOAL_AREA + NEAR/FAR_POST decision.
        // Live-neutral: ShotDirectionEngine never produces NEAR/FAR_POST
        // with zone GOAL_AREA, so no live decision changes — this only
        // removes the latent trap for future direct constructions.
        return when {

            decision.direction == ShotDirection.NEAR_POST ->
                KeeperBias.PROTECT_NEAR_POST

            decision.direction == ShotDirection.FAR_POST ->
                KeeperBias.PROTECT_FAR_POST

            decision.zone == ThreatZone.GOAL_AREA ->
                KeeperBias.TIGHTEN_GOAL_AREA

            decision.zone == ThreatZone.LEFT ->
                KeeperBias.SHADE_LEFT

            decision.zone == ThreatZone.RIGHT ->
                KeeperBias.SHADE_RIGHT

            else ->
                KeeperBias.HOLD_CENTER
        }
    }
}
