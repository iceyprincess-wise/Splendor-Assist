package com.assistant.adapter.smartassist

object FormationAdaptationEngine {

    fun analyze(
        tactical:TacticalIntelligenceResult,
        opponent:OpponentBehaviourLearningResult,
        player:PlayerTendencyLearningResult
    ,
        temporal:TemporalMemoryState
    ):FormationAdaptationResult {

        val temporalInfluence=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence+
                (0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)
            )/4f

        val confidence=(
            tactical.confidence*0.25f+
            opponent.confidence*0.20f+
            player.confidence*0.20f+
            temporalInfluence*0.35f
        ).coerceIn(0f,1f)

        return FormationAdaptationResult(
            confidence=confidence,
            adaptationScore=confidence,
            formationStable=confidence>=0.60f
        )
    }


    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
