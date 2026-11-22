package com.assistant

object ShootingHabitLearningEngine{

    fun analyze(
        shooting:ShootingLaneAnalysis,
        tactical:TacticalIntelligenceResult
    ,
        temporal:TemporalMemoryState
    ):ShootingHabitLearningResult{

        val temporalConfidence=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence
            )/3f

        val laneConfidence=
            if(shooting.lanes.isEmpty()) 0f else shooting.lanes.maxOf{it.confidence}

        val confidence=(
            tactical.confidence*0.40f+
            temporalConfidence*0.35f+
            laneConfidence*0.25f
        ).coerceIn(0f,1f)

        // No68-F-D1: longShotBias/boxShotBias are removed (see
        // ShootingHabitLearningResult.kt). They had zero readers repo-wide
        // and carried no information beyond confidence itself
        // (longShotBias was exactly confidence*0.5, boxShotBias was
        // confidence, or confidence*0.3 with no lanes) — a deterministic
        // rescaling cannot be wired to anything confidence does not
        // already feed. Only confidence is live: it flows into
        // RuntimeConfidenceCalibrationEngine.
        return ShootingHabitLearningResult(
            confidence=confidence
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
}
