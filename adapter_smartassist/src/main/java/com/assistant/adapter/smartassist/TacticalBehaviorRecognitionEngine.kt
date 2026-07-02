package com.assistant.adapter.smartassist

object TacticalBehaviorRecognitionEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        analytics:TacticalAnalyticsResult,
        formation:FormationResult,
        teamShape:TeamShapeResult
    ):TacticalBehaviorRecognitionResult{

        var score=0f

        score+=analytics.confidence
        score+=formation.confidence
        score+=if(formation.found)0.20f else 0f
        score+=teamShape.confidence
        score+=teamShape.compactness

        val confidence=clamp(score/3.2f)

        return TacticalBehaviorRecognitionResult(
            confidence=confidence
        )
    }
}
