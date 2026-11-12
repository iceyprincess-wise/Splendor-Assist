package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object CrossingLaneAnalysisEngine {

    data class CrossingLaneAnalysisEngineAmplifiedState(
        val sequence: Long,
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

        val result = ArrayList<CrossingLane>(graph.lanes.size)

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

        result.sortByDescending { it.confidence }
        val crossingLaneAnalysisResult = CrossingLaneAnalysis(result)
        publishCrossingLaneAnalysisEngineResult(crossingLaneAnalysisResult)
        return crossingLaneAnalysisResult
    }
    @Synchronized
    fun reset() {
        crossingLaneAnalysisSequence = 0L
        lastCrossingLaneAnalysisEngineState = null
    }

}

/* ========
CrowdingZoneDetector
======== */
/**
 * CrowdingZoneDetector
 *
 * Detects penalty-box and corner-kick scenarios where defenderDensity
 * peaks near 1.0 and confidence collapses simultaneously. In these
 * situations every upstream gameplay engine (FightingSpirit, Captaincy,
 * MagneticFeet) peaks at once because they share the same frame signals.
 * Device rendering load also spikes (many player models) causing real
 * FPS stalls that LagVerdictEngine cannot distinguish from device stress.
 *
 * This engine publishes to AdapterSignalBus so:
 *  - RuntimeDecisionLoop applies a duration saturation cap (45ms max).
 *  - LagVerdictEngine requires 2 extra confirmation polls before CHOKING.
 *
 * Pure detection: no gameplay authority, no bus writes except crowdingZone.
 * Called once per frame by RuntimeDecisionLoop BEFORE amplifiers.
 */
