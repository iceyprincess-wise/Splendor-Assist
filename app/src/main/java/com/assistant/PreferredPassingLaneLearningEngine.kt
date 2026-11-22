package com.assistant

object PreferredPassingLaneLearningEngine{

    fun analyze(
        graph:PassingLaneGraph,
        tactical:TacticalIntelligenceResult
    ,
        temporal:TemporalMemoryState
    ):PreferredPassingLaneLearningResult{

        // Kotlin Float.compareTo orders NaN above numbers and distinguishes
        // signed zero, matching maxOrNull on the mapped Float list.
        // No68-A-D8: ...except a NaN score must never WIN that max. A
        // single NaN lane (non-finite coords in the graph) would make
        // laneScore NaN, blendedLaneScore NaN, and confidence NaN into
        // the native calibration call. The graph now filters non-finite
        // coords (No68-A-D7); skipping non-finite scores here is the
        // defence in depth so one bad lane can never poison the result.
        var laneScore = 0f
        var hasLaneScore = false
        for (i in graph.lanes.indices) {
            val score = graph.lanes[i].score
            if (!score.isFinite()) continue
            if (!hasLaneScore || score.compareTo(laneScore) > 0) {
                laneScore = score
                hasLaneScore = true
            }
        }
        if (!hasLaneScore) laneScore = 0f

        // Blend weights: the best lane's own score counts 0.70, the
        // temporal prior (recent lane quality) 0.30. Tuned constants —
        // not re-tuned here; the empty-graph case keeps the prior (see
        // QA-6: whether confidence should read 0 with no lanes at all
        // is Ed's call, the temporal prior is deliberate as it stands).
        val temporalScore =
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence
            )/3f

        val blendedLaneScore=(laneScore*0.70f+temporalScore*0.30f).coerceIn(0f,1f)

        return PreferredPassingLaneLearningResult(
            // No68-A-D9: preferredLaneScore deleted — it was written
            // here and read NOWHERE in the whole repo (full-tree grep); the blended score already reaches the
            // calibration through confidence, so the field carried
            // nothing. Only confidence survives.
            confidence=((blendedLaneScore+tactical.confidence)/2f).coerceIn(0f,1f)
        )
    }
}
