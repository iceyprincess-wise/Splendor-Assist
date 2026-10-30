object TacticalIntelligenceEngine{

    private fun clamp(v:Float)=v.coerceIn(0f,1f)

    fun analyze(
        analytics:TacticalAnalyticsResult,
        behavior:TacticalBehaviorRecognitionResult,
        state:GameStateSnapshot
    ,
        temporal:TemporalMemoryState
    ,
        tacticalMap:TacticalMapResult
    ):TacticalIntelligenceResult{

        var score=0f

        score+=analytics.confidence
        score+=behavior.confidence
        score+=state.confidence
        // Tactical-map density: how much of the pitch the map actually
        // resolved. Previously the map grid was computed and discarded.
        val mapCells = tacticalMap.cells.size
        if (mapCells > 0) {
            var occupied = 0
            for (i in 0 until mapCells) if (tacticalMap.cells[i] > 0.05f) occupied++
            score += (occupied.toFloat() / mapCells.toFloat()).coerceIn(0f, 1f) * 0.25f
        }
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
/* ======
TacticalIntelligenceEngine Anchor
====== */

/* ========
TacticalMapGenerationEngine
======== */
