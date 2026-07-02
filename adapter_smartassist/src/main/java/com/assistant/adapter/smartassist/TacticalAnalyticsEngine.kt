package com.assistant.adapter.smartassist

object TacticalAnalyticsEngine{
    fun analyze(
        tacticalMap:TacticalMapResult,
        compactness:DefensiveCompactnessResult,
        wing:WingOverloadDetectionResult,
        central:CentralOverloadDetectionResult,
        pressing:PressingRecognitionResult,
        counterPress:CounterPressRecognitionResult,
        buildUp:BuildUpRecognitionResult,
        possession:PossessionStyleRecognitionResult
    ):TacticalAnalyticsResult{
        return TacticalAnalyticsResult()
    }
}
