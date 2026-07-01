package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class OverlapRun(
    val runner:TrackedPlayer,
    val supportX:Float,
    val supportY:Float,
    val confidence:Float,
    val viable:Boolean
)

data class OverlapDetectionResult(
    val overlaps:List<OverlapRun> = emptyList()
)

object OverlapDetectionEngine{

    fun analyze(
        runs:RunPredictionResult
    ):OverlapDetectionResult{

        val result=
            runs.runs.map{

                val speed=
                    hypot(
                        it.player.velocityX.toDouble(),
                        it.player.velocityY.toDouble()
                    ).toFloat()

                val confidence=
                    (
                        (speed/120f)+
                        it.confidence
                    ).coerceIn(0f,1f)

                OverlapRun(
                    runner=it.player,
                    supportX=it.predictedX,
                    supportY=it.predictedY,
                    confidence=confidence,
                    viable=confidence>=0.50f
                )

            }.sortedByDescending{
                it.confidence
            }

        return OverlapDetectionResult(result)
    }
}
