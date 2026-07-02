package com.assistant.adapter.smartassist

object OnlineParameterAdaptationEngine {

    fun analyze(
        calibration:RuntimeConfidenceCalibrationResult,
        state:GameStateSnapshot
    ,
        temporal:TemporalMemoryState
    ):OnlineParameterAdaptationResult {

        val gain=(
            calibration.calibratedConfidence*0.35f+
            state.confidence*0.15f+
            state.fieldConfidence*0.10f+
            temporal.exponentialMovingAverage*0.15f+
            temporal.rollingMean*0.10f+
            temporal.temporalConfidence*0.10f+
            (1f-temporal.confidenceVariance).coerceIn(0f,1f)*0.05f
        ).coerceIn(0f,1f)

        return OnlineParameterAdaptationResult(
            adaptationGain=gain
        )
    }


    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
