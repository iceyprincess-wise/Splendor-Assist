package com.assistant.adapter.smartassist

data class CrossingLane(
    val lane: PassingLane,
    val targetX: Float,
    val targetY: Float,
    val viable: Boolean,
    val confidence: Float
)

data class CrossingLaneAnalysis(
    val lanes: List<CrossingLane> = emptyList()
)

object CrossingLaneAnalysisEngine {

    fun analyze(
        graph: PassingLaneGraph
    ): CrossingLaneAnalysis {

        val result = ArrayList<CrossingLane>()

        graph.lanes.forEach { lane ->

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure)
                ).coerceIn(0f,1f)

            result += CrossingLane(
                lane = lane,
                targetX = lane.receiver.x,
                targetY = lane.receiver.y - 40f,
                viable = !lane.blocked && confidence >= 0.40f,
                confidence = confidence
            )
        }

        return CrossingLaneAnalysis(
            result.sortedByDescending {
                it.confidence
            }
        )
    }
}
