package com.assistant.adapter.smartassist

object PreferredPassingLaneLearningEngine{

    fun analyze(
        graph:PassingLaneGraph,
        tactical:TacticalIntelligenceResult
    ,
        temporal:TemporalMemoryState
    ):PreferredPassingLaneLearningResult{

        val laneScore =
            graph.lanes
                .map { it.score }
                .maxOrNull()
                ?: 0f

        val temporalScore =
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence
            )/3f

        val blendedLaneScore=(laneScore*0.70f+temporalScore*0.30f).coerceIn(0f,1f)

        return PreferredPassingLaneLearningResult(
            confidence=((blendedLaneScore+tactical.confidence)/2f).coerceIn(0f,1f),
            preferredLaneScore=blendedLaneScore
        )
    }


    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
