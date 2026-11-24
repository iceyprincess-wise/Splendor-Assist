package com.assistant

object TacticalBehaviorRecognitionEngine{
    private val outBuffer = FloatArray(1)
    fun analyze(analytics:TacticalAnalyticsResult, formation:FormationResult, teamShape:TeamShapeResult):TacticalBehaviorRecognitionResult {
        // Phase 1 NativeReadinessGuard (part-1 pattern, AuthorityArbitrationEngine.kt:25):
        // a library-load failure must not fault VisionCore.process() every frame.
        // Return the default zero-confidence result — do NOT read the shared
        // outBuffer here, it still holds the previous frame's value (stale).
        if (!com.assistant.NativeBridge.isNativeReady()) return TacticalBehaviorRecognitionResult()
        com.assistant.NativeBridge.nativeTacticalBehavior(analytics.confidence, formation.confidence, formation.found, teamShape.confidence, teamShape.compactness, outBuffer)
        return TacticalBehaviorRecognitionResult(confidence = outBuffer[0])
    }
}
