package com.assistant.adapter.smartassist

data class OffsideRisk(
    val lane:PassingLane,
    val risk:Float,
    val safe:Boolean
)

data class OffsideRiskEstimationResult(
    val lanes:List<OffsideRisk> = emptyList()
)

object OffsideRiskEstimationEngine{

    fun analyze(
        graph:PassingLaneGraph
    ):OffsideRiskEstimationResult{

        val result=
            graph.lanes.map{

                val risk=
                    (
                        (it.distance/900f)+
                        it.pressure
                    ).coerceIn(0f,1f)

                OffsideRisk(
                    lane=it,
                    risk=risk,
                    safe=risk<0.50f
                )

            }.sortedBy{
                it.risk
            }

        return OffsideRiskEstimationResult(result)
    }
}
