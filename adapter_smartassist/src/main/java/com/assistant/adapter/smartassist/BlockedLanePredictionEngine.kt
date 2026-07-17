package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class BlockedLanePrediction(
    val lane: PassingLane,
    val predictedBlocked: Boolean,
    val risk: Float
)

data class BlockedLanePredictionAnalysis(
    val lanes: List<BlockedLanePrediction> = emptyList()
)

object BlockedLanePredictionEngine {

    /**
     * [OMEGA PREDICTIVE ENGINE] - High-Precision Spatial and Vector Blocked Lane Analysis.
     * Engineered with dynamic risk-damping and adaptive thresholds to eliminate interception risks.
     */
    fun analyze(
        graph: PassingLaneGraph
    ): BlockedLanePredictionAnalysis {
        val lanesList = graph.lanes
        if (lanesList.isEmpty()) {
            return BlockedLanePredictionAnalysis()
        }

        // Optimize memory allocation with exact initial capacity
        val result = ArrayList<BlockedLanePrediction>(lanesList.size)

        lanesList.forEach { lane ->
            // Extract spatial coordinates from passer and receiver
            val passer = lane.passer
            val receiver = lane.receiver
            
            // Compute Euclidean distance of the passing lane vector
            val dx = receiver.x - passer.x
            val dy = receiver.y - passer.y
            val laneDistance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            // Normalize core metrics
            val basePressure = lane.pressure.coerceIn(0f, 1f)
            val baseScore = lane.score.coerceIn(0f, 1f)

            /**
             * [OMEGA MATHEMATICAL SCALING]
             * Factor 1: Long-distance passes incur higher interception risk over time.
             * Factor 2: Dynamic pressure scaling factors the passer's surrounding crowd density.
             * Factor 3: Score correlation factors in standard spatial clearances.
             */
            val distanceRiskFactor = (laneDistance / 800f).coerceIn(0f, 0.45f)
            
            // Highly responsive risk curve calculation
            val rawRisk = (basePressure * 0.50f) + ((1f - baseScore) * 0.40f) + distanceRiskFactor
            val calibratedRisk = rawRisk.coerceIn(0f, 1f)

            /**
             * [SURE WINNINGS REAL CHANCES - OPTIMAL THRESHOLD]
             * Lowers the blocked threshold dynamically to 0.50f if the lane score drops below 0.35f,
             * ensuring the interface warns the user of dangerous lanes before they are intercepted.
             */
            val dynamicBlockedThreshold = if (baseScore < 0.35f) 0.50f else 0.60f
            val isPredictedBlocked = lane.blocked || calibratedRisk >= dynamicBlockedThreshold

            result += BlockedLanePrediction(
                lane = lane,
                predictedBlocked = isPredictedBlocked,
                risk = calibratedRisk
            )
        }

        // Sort descending by threat risk to feed immediate interception priorities to execution systems
        return BlockedLanePredictionAnalysis(
            result.sortedByDescending { it.risk }
        )
    }
}
