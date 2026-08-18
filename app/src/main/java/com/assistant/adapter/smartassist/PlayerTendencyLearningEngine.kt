package com.assistant.adapter.smartassist

object PlayerTendencyLearningEngine{

    fun analyze(
        tactical:TacticalIntelligenceResult,
        state:GameStateSnapshot
    ,
        temporal:TemporalMemoryState
    ):PlayerTendencyLearningResult{

        val temporalConfidence =
            (
                temporal.exponentialMovingAverage*0.30f+
                temporal.rollingMean*0.25f+
                temporal.temporalConfidence*0.20f+
                (0.5f+temporal.confidenceSlope*0.5f).coerceIn(0f,1f)*0.15f+
                (1f-temporal.confidenceVariance).coerceIn(0f,1f)*0.10f
            ).coerceIn(0f,1f)

        val confidence=(
            tactical.confidence*0.35f+
            state.confidence*0.15f+
            state.fieldConfidence*0.10f+
            temporalConfidence*0.40f
        ).coerceIn(0f,1f)

        return PlayerTendencyLearningResult(
            confidence=confidence,
            passBias=(confidence*(0.60f+state.confidence*0.40f)).coerceIn(0f,1f),
            dribbleBias=(confidence*(0.50f+state.fieldConfidence*0.50f)).coerceIn(0f,1f),
            shootBias=(confidence*(0.40f+state.confidence*0.60f)).coerceIn(0f,1f)
        )
    }


    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
