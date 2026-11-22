package com.assistant

object TouchRecoveryEngine {

    fun recover(
        pressure: Int,
        strength: Int
    ): TouchRecoveryResult {

        val factor = (strength.coerceIn(0, 100) / 100f)
        val pressureFactor = (pressure.coerceIn(0, 100) / 100f)

        // No68-E2-D17/D18: the shield and balance outputs are gone —
        // they were the identical formula (2 + factor*4 + pressure*2),
        // so the two outputs were always equal, and no caller anywhere
        // read either of them (both call sites read recoveryBoost only).
        // The vacuous lower-bound guard is gone too: the minimum output
        // is 2f. Range and coefficients unchanged: recovery 2..10.
        val calculatedRecovery = 2f + (factor * 5.00f) + (pressureFactor * 3.00f)

        return TouchRecoveryResult(
            recoveryBoost = calculatedRecovery
        )
    }
}
