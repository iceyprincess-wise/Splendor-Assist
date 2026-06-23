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

        val responseBoost=
            (strength.coerceIn(0,100)/100f)

        val predictiveFactor=
            0.08f + (responseBoost*0.22f)

        val compensatedX=
            endX + dx*predictiveFactor

        val compensatedY=
            endY + dy*predictiveFactor

        val reducedDuration=
            (duration*(1f-(responseBoost*0.35f)))
                .toLong()
                .coerceAtLeast(20L)

        val confidence=
            (0.70f + responseBoost*0.30f)
                .coerceAtMost(1f)

        val urgency=
            (distance/8f)
                .toInt()
                .coerceAtMost(100)

        return CompensationResult(
            compensatedX,
            compensatedY,
            reducedDuration,
            confidence,
            urgency
        )
    }
}
