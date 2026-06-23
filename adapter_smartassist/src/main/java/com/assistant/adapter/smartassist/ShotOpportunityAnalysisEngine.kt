package com.assistant.adapter.smartassist

data class ShotOpportunityResult(
    val confidence:Float,
    val openSideScore:Float,
    val pressureScore:Float
)

object ShotOpportunityAnalysisEngine {

    fun analyze(
        distance:Float,
        pressure:Float
    ):ShotOpportunityResult {

        val confidence=
            (1f-(distance/1000f))
                .coerceIn(0f,1f)

        return ShotOpportunityResult(
            confidence=confidence,
            openSideScore=(1f-pressure).coerceIn(0f,1f),
            pressureScore=pressure.coerceIn(0f,1f)
        )
    }
}
