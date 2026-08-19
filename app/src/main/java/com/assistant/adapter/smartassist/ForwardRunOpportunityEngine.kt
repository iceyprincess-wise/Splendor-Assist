package com.assistant.adapter.smartassist

data class ForwardRunResult(
    val runBoost:Float,
    val laneScore:Float,
    val confidence:Float
)

object ForwardRunOpportunityEngine {

    fun evaluate(
        distance:Float,
        strength:Int
    ):ForwardRunResult {

        val factor=
            strength.coerceIn(0,100)/100f

        return ForwardRunResult(
            runBoost=
                1f + (factor * 0.80f),

            laneScore=
                (distance/1000f)
                    .coerceAtMost(1f),

            confidence=
                (0.65f + factor*0.35f)
                    .coerceAtMost(1f)
        )
    }
}
