package com.assistant

object RuntimeConfidenceCalibrationEngine {
    private val outBuffer = FloatArray(1)
    fun analyze(tactical:TacticalIntelligenceResult, formation:FormationAdaptationResult, passing:PreferredPassingLaneLearningResult, shooting:ShootingHabitLearningResult, temporal:TemporalMemoryState):RuntimeConfidenceCalibrationResult {
        com.assistant.NativeBridge.nativeRuntimeConfidenceCalibration(tactical.confidence, formation.confidence, passing.confidence, shooting.confidence, temporal.exponentialMovingAverage, temporal.rollingMean, temporal.temporalConfidence, outBuffer)
        return RuntimeConfidenceCalibrationResult(calibratedConfidence = outBuffer[0])
    }
}
