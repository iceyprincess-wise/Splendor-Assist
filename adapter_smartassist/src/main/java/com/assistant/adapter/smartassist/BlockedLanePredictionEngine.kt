package com.assistant.adapter.smartassist

data class BlockedLanePrediction(
    val lane: PassingLane,
    val predictedBlocked: Boolean,
    val risk: Float
)

data class BlockedLanePredictionAnalysis(
    val lanes: List<BlockedLanePrediction> = emptyList()
)

object BlockedLanePredictionEngine {

    fun analyze(
        graph: PassingLaneGraph
    ): BlockedLanePredictionAnalysis {

        val result = ArrayList<BlockedLanePrediction>()

        graph.lanes.forEach { lane ->

            val risk =
                (
                    lane.pressure +
                    ((1f - lane.score) * 0.50f)
                ).coerceIn(0f,1f)

            result += BlockedLanePrediction(
                lane = lane,
                predictedBlocked =
                    lane.blocked || risk >= 0.65f,
                risk = risk
            )
        }

        return BlockedLanePredictionAnalysis(
            result.sortedByDescending {
                it.risk
            }
        )
    }
}
