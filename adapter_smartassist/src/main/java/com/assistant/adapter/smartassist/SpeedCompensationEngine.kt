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

        val containment=
            if(abs(angle) > 45f)
                15f
            else
                -15f

        return SpeedCompensationResult(
            containmentAngle = containment,
            executionBoost = 1f + (factor * 0.50f),
            interceptionProtection = factor,
            pressureCompensation = factor,
            laneCompensation = factor
        )
    }
}
