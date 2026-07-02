package com.assistant.adapter.smartassist

object TacticalIntelligenceEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        analytics:TacticalAnalyticsResult,
        behavior:TacticalBehaviorRecognitionResult,
        state:GameStateSnapshot
    ,
        temporal:TemporalMemoryState
    ):TacticalIntelligenceResult{

        var score=0f

        score+=analytics.confidence
        score+=behavior.confidence
        score+=state.confidence
        score+=state.fieldConfidence
        score+=if(state.ballDetected)0.10f else 0f
        score+=if(state.playerDetected)0.10f else 0f
        score+=if(state.goalDetected)0.05f else 0f
        score+=if(state.goalkeeperDetected)0.05f else 0f

        score+=temporal.exponentialMovingAverage
        score+=temporal.rollingMean
        score+=temporal.temporalConfidence
        score+=(1f-temporal.confidenceVariance).coerceIn(0f,1f)
        score+=(0.5f+temporal.confidenceTrend*0.5f).coerceIn(0f,1f)


        val confidence=clamp(score/3.3f)

        return TacticalIntelligenceResult(
            confidence=confidence
        )
    }
}
