object PreferredPassingLaneLearningEngine{

    fun analyze(
        graph:PassingLaneGraph,
        tactical:TacticalIntelligenceResult
    ,
        temporal:TemporalMemoryState
    ):PreferredPassingLaneLearningResult{

        // Kotlin Float.compareTo orders NaN above numbers and distinguishes
        // signed zero, matching maxOrNull on the mapped Float list.
        var laneScore = 0f
        var hasLaneScore = false
        for (i in graph.lanes.indices) {
            val score = graph.lanes[i].score
            if (!hasLaneScore || score.compareTo(laneScore) > 0) {
                laneScore = score
                hasLaneScore = true
            }
        }
        if (!hasLaneScore) laneScore = 0f

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
/* ======
PreferredPassingLaneLearningEngine Anchor
====== */

/* ========
PressingRecognitionEngine
======== */
