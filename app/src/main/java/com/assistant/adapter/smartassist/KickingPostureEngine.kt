package com.assistant.adapter.smartassist

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class PostureCorrectionResult(
    val correctedX: Float,
    val correctedY: Float,
    val balanceScore: Float,
    val requiresAdjustTouch: Boolean
)

object KickingPostureEngine {

    private const val RAD_TO_DEG = 57.29577951308232f
    private const val DEG_TO_RAD = 0.017453292519943295f

    fun evaluateAndCorrect(
        carrierX: Float, carrierY: Float,
        carrierVx: Float, carrierVy: Float,
        targetX: Float, targetY: Float
    ): PostureCorrectionResult {
        val dx = targetX - carrierX
        val dy = targetY - carrierY

        val targetAngleRad = atan2(dy.toDouble(), dx.toDouble()).toFloat()

        val vMagSq = carrierVx * carrierVx + carrierVy * carrierVy
        val facingAngleRad = if (vMagSq > 0.01f) {
            atan2(carrierVy.toDouble(), carrierVx.toDouble()).toFloat()
        } else {
            targetAngleRad
        }

        var diffRad = targetAngleRad - facingAngleRad
        while (diffRad > Math.PI.toFloat()) diffRad -= (2f * Math.PI.toFloat())
        while (diffRad < -Math.PI.toFloat()) diffRad += (2f * Math.PI.toFloat())

        val absDiffDeg = abs(diffRad) * RAD_TO_DEG
        val balanceScore = (1f - ((absDiffDeg - 45f).coerceAtLeast(0f) / 135f)).coerceIn(0f, 1f)

        if (absDiffDeg > 60f) {
            val maxCorrectionRad = 25f * DEG_TO_RAD
            val sign = if (diffRad > 0f) 1f else -1f
            val correctedAngleRad = targetAngleRad - (sign * maxCorrectionRad)

            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val newX = (carrierX + cos(correctedAngleRad.toDouble()).toFloat() * dist).coerceIn(0f, 1650f)
            val newY = (carrierY + sin(correctedAngleRad.toDouble()).toFloat() * dist).coerceIn(0f, 720f)

            return PostureCorrectionResult(
                correctedX = newX,
                correctedY = newY,
                balanceScore = balanceScore,
                requiresAdjustTouch = absDiffDeg > 110f
            )
        }

        return PostureCorrectionResult(
            correctedX = targetX,
            correctedY = targetY,
            balanceScore = balanceScore,
            requiresAdjustTouch = false
        )
    }
}
