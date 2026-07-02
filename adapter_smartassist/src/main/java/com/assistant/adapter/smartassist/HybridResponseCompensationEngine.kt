package com.assistant.adapter.smartassist

import kotlin.math.*

data class CompensationResult(
    val endX:Float,
    val endY:Float,
    val duration:Long,
    val confidence:Float,
    val urgency:Int
)

object HybridResponseCompensationEngine {

    fun compensate(
        startX:Float,
        startY:Float,
        endX:Float,
        endY:Float,
        duration:Long,
        strength:Int
    ): CompensationResult {

        val dx=endX-startX
        val dy=endY-startY

        val distance=hypot(dx,dy)

        val worldState=

            Phase3WorldStateStore.current()

        val temporal=
            worldState.temporalMemoryState

        val adaptiveConfidence=
            worldState.runtimeConfidenceCalibrationResult.calibratedConfidence

        val adaptationGain=
            worldState.onlineParameterAdaptationResult.adaptationGain

        val responseBoost =
            (
                adaptiveConfidence +
                adaptationGain +
                temporal.temporalConfidence +
                temporal.exponentialMovingAverage +
                temporal.rollingMean +
                (strength.coerceIn(0,100) / 100f)
            ) / 6f

        val predictiveFactor=
            (
                temporal.exponentialMovingAverage+
                temporal.rollingMean+
                temporal.temporalConfidence+
                adaptiveConfidence+
                adaptationGain+
                responseBoost
            )/6f

        val compensatedX=
            endX + dx*predictiveFactor

        val compensatedY=
            endY + dy*predictiveFactor

        val durationScale=
            (1f-(predictiveFactor*temporal.temporalConfidence))
                .coerceIn(0.15f,1f)

        val reducedDuration=
            (duration*durationScale)
                .toLong()
                .coerceAtLeast(8L)

        val confidence=
            (
                adaptiveConfidence+
                temporal.temporalConfidence+
                predictiveFactor
            )/3f

        val urgency=
            (
                (distance/8f)*
                (1f+temporal.confidenceTrend)*
                (1f+adaptationGain)
            )
                .toInt()
                .coerceIn(0,100)

        return CompensationResult(
            compensatedX,
            compensatedY,
            reducedDuration,
            confidence,
            urgency
        )
    }
}
