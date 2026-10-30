object TacticalBehaviorRecognitionEngine{
    private val outBuffer = FloatArray(1)
    fun analyze(analytics:TacticalAnalyticsResult, formation:FormationResult, teamShape:TeamShapeResult):TacticalBehaviorRecognitionResult {
        com.assistant.NativeBridge.nativeTacticalBehavior(analytics.confidence, formation.confidence, formation.found, teamShape.confidence, teamShape.compactness, outBuffer)
        return TacticalBehaviorRecognitionResult(confidence = outBuffer[0])
    }
}
/* ======
TacticalBehaviorRecognitionEngine Anchor
====== */

/* ========
TelemetryCoordinator
======== */
