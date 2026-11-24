package com.assistant

object RuntimeConfidenceCalibrationEngine {
    private val outBuffer = FloatArray(1)
    fun analyze(tactical:TacticalIntelligenceResult, formation:FormationAdaptationResult, passing:PreferredPassingLaneLearningResult, shooting:ShootingHabitLearningResult, temporal:TemporalMemoryState):RuntimeConfidenceCalibrationResult {
        // Phase 1 NativeReadinessGuard (part-1 pattern, AuthorityArbitrationEngine.kt:25):
        // a library-load failure must not fault VisionCore.process() every frame.
        // Return the default zero-confidence result — do NOT read the shared
        // outBuffer here, it still holds the previous frame's value (stale).
        if (!com.assistant.NativeBridge.isNativeReady()) return RuntimeConfidenceCalibrationResult()
        com.assistant.NativeBridge.nativeRuntimeConfidenceCalibration(tactical.confidence, formation.confidence, passing.confidence, shooting.confidence, temporal.exponentialMovingAverage, temporal.rollingMean, temporal.temporalConfidence, outBuffer)
        return RuntimeConfidenceCalibrationResult(calibratedConfidence = outBuffer[0])
    }
}
