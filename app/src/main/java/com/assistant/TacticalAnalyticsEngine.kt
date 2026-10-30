object TacticalAnalyticsEngine{
    private val outBuffer = FloatArray(1)
    fun analyze(tacticalMap:TacticalMapResult, compactness:DefensiveCompactnessResult, wing:WingOverloadDetectionResult, central:CentralOverloadDetectionResult, pressing:PressingRecognitionResult, counterPress:CounterPressRecognitionResult, buildUp:BuildUpRecognitionResult, possession:PossessionStyleRecognitionResult):TacticalAnalyticsResult {
        com.assistant.NativeBridge.nativeTacticalAnalytics(tacticalMap.confidence, compactness.confidence, compactness.compactness, wing.confidence, wing.overloaded, central.confidence, central.overloaded, pressing.confidence, pressing.detected, counterPress.confidence, counterPress.detected, buildUp.confidence, buildUp.detected, possession.confidence, possession.detected, outBuffer)
        // Real analytics produced: analyticsProduced was declared and printed
        // but never incremented, so the ANALYTICS counter read 0 no matter how
        // many analyses ran.
        com.assistant.diagnostic.RuntimeMetricsRegistry.analyticsProduced.incrementAndGet()
        return TacticalAnalyticsResult(confidence = outBuffer[0])
    }
}
/* ======
TacticalAnalyticsEngine Anchor
====== */

/* ========
TacticalBehaviorRecognitionEngine
======== */
