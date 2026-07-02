package com.assistant.adapter.smartassist

object OpponentBehaviourLearningEngine{

    fun analyze(
        tactical:TacticalIntelligenceResult,
        state:GameStateSnapshot
    ,
        temporal:TemporalMemoryState
    ):OpponentBehaviourLearningResult{

        val temporalConfidence =
            (
                temporal.exponentialMovingAverage*0.30f+
                temporal.rollingMean*0.20f+
                temporal.temporalConfidence*0.20f+
                (1f-temporal.confidenceVariance).coerceIn(0f,1f)*0.15f+
                (0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)*0.15f
            ).coerceIn(0f,1f)

        val confidence=(
            tactical.confidence*0.35f+
            state.confidence*0.15f+
            state.fieldConfidence*0.10f+
            temporalConfidence*0.40f
        ).coerceIn(0f,1f)

        return OpponentBehaviourLearningResult(
            confidence=confidence,
            aggression=confidence,
            pressFrequency=(confidence*(0.70f+state.fieldConfidence*0.30f)).coerceIn(0f,1f),
            transitionSpeed=(confidence*(0.60f+state.confidence*0.40f)).coerceIn(0f,1f)
        )
    }


    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
