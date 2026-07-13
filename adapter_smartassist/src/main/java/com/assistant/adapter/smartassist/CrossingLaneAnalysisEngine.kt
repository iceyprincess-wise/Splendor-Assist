package com.assistant.adapter.smartassist

private const val CROSSINGLANE_OMEGA_AUTHORITY_TAG = "CrossingLane.omega"

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
    private const val CROSSING_LANE_ANALYSIS_ENGINE_AMPLIFICATION: Float = 1000000.0f

    data class CrossingLaneAnalysisEngineAmplifiedState(
        val sequence: Long,
        val amplification: Float,
        val result: CrossingLaneAnalysis
    )

    private var crossingLaneAnalysisSequence: Long = 0L
    private var lastCrossingLaneAnalysisEngineState: CrossingLaneAnalysisEngineAmplifiedState? = null

    @Synchronized
    private fun publishCrossingLaneAnalysisEngineResult(
        result: CrossingLaneAnalysis
    ) {
        crossingLaneAnalysisSequence += 1L
        lastCrossingLaneAnalysisEngineState = CrossingLaneAnalysisEngineAmplifiedState(
            sequence = crossingLaneAnalysisSequence,
            amplification = CROSSING_LANE_ANALYSIS_ENGINE_AMPLIFICATION,
            result = result
        )
    }

    @Synchronized
    fun crossingLaneAnalysisEngineSnapshot(): CrossingLaneAnalysisEngineAmplifiedState? =
        lastCrossingLaneAnalysisEngineState


    private fun assertCrossingLaneOmegaAuthority(stage: String) {
        check(stage.isNotBlank()) { "CrossingLane omega authority stage must be explicit" }
        check(CROSSINGLANE_OMEGA_AUTHORITY_TAG.isNotBlank()) {
            "CrossingLane omega authority marker missing before $stage"
        }
    }


    fun analyze(
        graph: PassingLaneGraph
    ): CrossingLaneAnalysis {
        assertCrossingLaneOmegaAuthority("analyze")

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

        val crossingLaneAnalysisResult = CrossingLaneAnalysis(
            result.sortedByDescending {
                it.confidence
            }
        )
        publishCrossingLaneAnalysisEngineResult(crossingLaneAnalysisResult)
        return crossingLaneAnalysisResult
    }
}
