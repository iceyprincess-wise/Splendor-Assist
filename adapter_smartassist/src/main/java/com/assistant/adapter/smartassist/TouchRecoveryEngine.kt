package com.assistant.adapter.smartassist

data class TouchRecoveryResult(
    val recoveryBoost:Float,
    val shieldStrength:Float,
    val balanceStrength:Float
)

object TouchRecoveryEngine {

    fun recover(
        pressure:Int,
        strength:Int
    ): TouchRecoveryResult {

        val factor=(strength.coerceIn(0,100)/100f)

        return TouchRecoveryResult(
            recoveryBoost=1f+(factor*0.60f),
            shieldStrength=0.40f+(factor*0.60f),
            balanceStrength=0.40f+(factor*0.60f)
        )
    }
}
