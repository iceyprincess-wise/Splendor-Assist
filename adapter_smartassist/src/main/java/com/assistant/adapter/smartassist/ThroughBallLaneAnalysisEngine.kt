package com.assistant.adapter.smartassist

data class ThroughBallLane(
    val lane: PassingLane,
    val viable: Boolean,
    val leadDistance: Float,
    val confidence: Float
)

data class ThroughBallLaneAnalysis(
    val lanes: List<ThroughBallLane> = emptyList()
)

object ThroughBallLaneAnalysisEngine {

    fun analyze(
        graph: PassingLaneGraph
    ): ThroughBallLaneAnalysis {

        val result = ArrayList<ThroughBallLane>()

        graph.lanes.forEach { lane ->

            val leadDistance =
                (lane.distance * 0.18f)
                    .coerceIn(15f,120f)

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure)
                ).coerceIn(0f,1f)

            result += ThroughBallLane(
                lane = lane,
                viable = !lane.blocked && confidence >= 0.35f,
                leadDistance = leadDistance,
                confidence = confidence
            )
        }

        return ThroughBallLaneAnalysis(
            result.sortedByDescending {
                it.confidence
            }
        )
    }
}
