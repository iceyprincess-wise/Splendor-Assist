package com.assistant.adapter.smartassist

object TacticalAnalyticsEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

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

        var score=0f

        score+=tacticalMap.confidence
        score+=compactness.confidence
        score+=compactness.compactness
        score+=wing.confidence
        score+=if(wing.overloaded)0.05f else 0f
        score+=central.confidence
        score+=if(central.overloaded)0.05f else 0f
        score+=pressing.confidence
        score+=if(pressing.detected)0.05f else 0f
        score+=counterPress.confidence
        score+=if(counterPress.detected)0.05f else 0f
        score+=buildUp.confidence
        score+=if(buildUp.detected)0.05f else 0f
        score+=possession.confidence
        score+=if(possession.detected)0.05f else 0f

        val confidence=clamp(score/8.4f)

        return TacticalAnalyticsResult(
            confidence=confidence
        )
    }
}
