package com.assistant.adapter.smartassist

import kotlin.math.*

data class SpeedCompensationResult(
    val containmentAngle:Float,
    val executionBoost:Float,
    val interceptionProtection:Float,
    val pressureCompensation:Float,
    val laneCompensation:Float
)

object SpeedCompensationEngine {

    fun compensate(
        distance:Float,
        angle:Float,
        strength:Int
    ): SpeedCompensationResult {

        val factor=(strength.coerceIn(0,100)/100f)
        val distanceFactor=(distance.coerceIn(0f,1000f)/1000f)

        val containment=
            if(abs(angle) > 45f)
                15f
            else
                -15f

        return SpeedCompensationResult(
            containmentAngle = containment,
            executionBoost = 9.0f + (factor * 14.0f) + (distanceFactor * 7.0f),
            interceptionProtection = factor * 18.0f,
            pressureCompensation = factor * 18.0f,
            laneCompensation = factor * 18.0f
        )
    }
}
