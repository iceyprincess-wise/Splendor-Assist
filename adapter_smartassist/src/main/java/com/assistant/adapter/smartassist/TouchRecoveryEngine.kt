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
        val pressureFactor=(pressure.coerceIn(0,100)/100f)

        return TouchRecoveryResult(
            recoveryBoost=2f+(factor*5.00f)+(pressureFactor*3.00f),
            shieldStrength=2f+(factor*4.00f)+(pressureFactor*2.00f),
            balanceStrength=2f+(factor*4.00f)+(pressureFactor*2.00f)
        )
    }
}
