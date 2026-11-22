package com.assistant

import kotlin.math.hypot

object OverlapDetectionEngine{

    fun analyze(
        runs:RunPredictionResult
    ):OverlapDetectionResult{

        val result = ArrayList<OverlapRun>(runs.runs.size)
        for (i in runs.runs.indices) {
            val run = runs.runs[i]
            val speed = hypot(run.player.velocityX.toDouble(), run.player.velocityY.toDouble()).toFloat()
            val confidence = ((speed / 120f) + run.confidence).coerceIn(0f, 1f)
            result.add(OverlapRun(run.player, run.predictedX, run.predictedY, confidence, confidence >= 0.50f))
        }
        result.sortByDescending { it.confidence }
        return OverlapDetectionResult(result)
    }
}
