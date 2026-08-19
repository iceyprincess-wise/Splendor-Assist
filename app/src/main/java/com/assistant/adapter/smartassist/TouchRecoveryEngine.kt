package com.assistant.adapter.smartassist

import kotlin.random.Random

data class TouchRecoveryResult(
    val recoveryBoost: Float,
    val shieldStrength: Float,
    val balanceStrength: Float
)

object TouchRecoveryEngine {

    fun recover(
        pressure: Int,
        strength: Int
    ): TouchRecoveryResult {

        val factor = (strength.coerceIn(0, 100) / 100f)
        val pressureFactor = (pressure.coerceIn(0, 100) / 100f)

        // Inject subtle sub-decimal float variance to break up repetitive data signatures
        val recoveryNoise = Random.nextFloat() * 0.16f - 0.08f // +/- 0.08 variance bounds
        val shieldNoise = Random.nextFloat() * 0.12f - 0.06f
        val balanceNoise = Random.nextFloat() * 0.12f - 0.06f

        val calculatedRecovery = (2f + (factor * 5.00f) + (pressureFactor * 3.00f) + recoveryNoise).coerceAtLeast(0f)
        val calculatedShield = (2f + (factor * 4.00f) + (pressureFactor * 2.00f) + shieldNoise).coerceAtLeast(0f)
        val calculatedBalance = (2f + (factor * 4.00f) + (pressureFactor * 2.00f) + balanceNoise).coerceAtLeast(0f)

        return TouchRecoveryResult(
            recoveryBoost = calculatedRecovery,
            shieldStrength = calculatedShield,
            balanceStrength = calculatedBalance
        )
    }
}
