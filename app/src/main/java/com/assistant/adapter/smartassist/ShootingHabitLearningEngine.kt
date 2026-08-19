package com.assistant.adapter.smartassist

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

        return ShootingHabitLearningResult(
            confidence=confidence,
            longShotBias=confidence*0.5f,
            boxShotBias=
                if(shooting.lanes.isEmpty())
                    confidence*0.3f
                else
                    confidence
        )
    }


    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration.
}
