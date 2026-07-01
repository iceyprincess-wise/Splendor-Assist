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
            0.90f + (responseBoost*2.80f)

        val compensatedX=
            endX + dx*predictiveFactor

        val compensatedY=
            endY + dy*predictiveFactor

        val reducedDuration=
            (duration*(1f-(responseBoost*0.72f)))
                .toLong()
                .coerceAtLeast(8L)

        val confidence=
            (0.82f + responseBoost*0.18f)
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
