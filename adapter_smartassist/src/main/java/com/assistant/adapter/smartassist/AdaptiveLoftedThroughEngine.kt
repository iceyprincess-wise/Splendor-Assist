package com.assistant.adapter.smartassist

import kotlin.math.hypot
import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

class AdaptiveLoftedThroughEngine(
    private val inputEngine:LatencyDefeatingInputEngine
){

    fun executeOptimalLoftedThrough(
        passButtonX:Float,
        passButtonY:Float,
        attackerX:Float,
        attackerY:Float,
        attackerVx:Float,
        attackerVy:Float,
        pitchHeight:Float
    ){

        val lookAheadTime = 450f

        val targetLandingX =
            attackerX + (attackerVx * lookAheadTime)

        val targetLandingY =
            (attackerY + (attackerVy * lookAheadTime))
                .coerceIn(0f,pitchHeight)

        val distanceToTarget =
            hypot(
                (targetLandingX-passButtonX).toDouble(),
                (targetLandingY-passButtonY).toDouble()
            ).toFloat()

        val optimizedDuration =
            (90L + (distanceToTarget * 0.12f).toLong())
                .coerceIn(90L,160L)

        val loftedVectorY =
            passButtonY - 100f

        inputEngine.injectZeroLatencySwipe(
            passButtonX,
            passButtonY,
            passButtonX,
            loftedVectorY,
            optimizedDuration
        )
    }
}
