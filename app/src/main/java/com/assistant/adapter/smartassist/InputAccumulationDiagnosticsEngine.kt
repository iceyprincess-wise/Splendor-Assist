package com.assistant.adapter.smartassist

data class InputDiagnosticsResult(
    val overchargeRisk:Float,
    val latencyScore:Float,
    val stabilityScore:Float
)

object InputAccumulationDiagnosticsEngine {

    fun analyze(
        duration:Long
    ):InputDiagnosticsResult {

        val risk=
            (duration/300f)
                .coerceIn(0f,1f)

        return InputDiagnosticsResult(
            overchargeRisk=risk,
            latencyScore=((1f-risk)*4f),
            stabilityScore=((1f-risk)*4f)
        )
    }
}
