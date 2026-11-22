package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object InputAccumulationDiagnosticsEngine {

    fun analyze(
        duration:Long
    ):InputDiagnosticsResult {

        val risk=
            (duration/300f)
                .coerceIn(0f,1f)

        return InputDiagnosticsResult(
            stabilityScore=((1f-risk)*4f)
        )
    }
}


