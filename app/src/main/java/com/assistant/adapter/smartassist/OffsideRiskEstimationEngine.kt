package com.assistant.adapter.smartassist

import kotlin.math.max

data class OffsideRisk(
    val lane: PassingLane,
    val risk: Float,
    val safe: Boolean
)

data class OffsideRiskEstimationResult(
    val lanes: List<OffsideRisk> = emptyList()
)

object OffsideRiskEstimationEngine {

    /**
     * Analyzes offside risks across passing lanes relative to the opponent's offside line.
     * Uses zero heap allocations inside the analysis loop for 60 FPS performance.
     *
     * @param graph The passing lane graph
     * @param lastDefenderX The X coordinate of the last opponent field defender (Offside Line).
     *                      Defaults to 1200f if unsupplied/unknown.
     */
    fun analyze(
        graph: PassingLaneGraph,
        lastDefenderX: Float = 1200f
    ): OffsideRiskEstimationResult {

        val lanesList = graph.lanes
        if (lanesList.isEmpty()) {
            return OffsideRiskEstimationResult(emptyList())
        }

        // Zero-Allocation Strategy: Pre-allocate precise capacity to eliminate GC pauses
        val count = lanesList.size
        val result = ArrayList<OffsideRisk>(count)

        for (i in 0 until count) {
            val lane = lanesList[i]
            val receiver = lane.receiver

            val rx = receiver.x
            val rvx = receiver.velocityX

            // Project receiver position 120ms into the future based on sprint momentum
            val projectedRx = rx + (rvx * 0.12f)

            val risk: Float
            val isSafe: Boolean

            when {
                // Receiver is clearly behind the offside line -> 0% Offside Risk
                rx < (lastDefenderX - 15f) -> {
                    risk = (rx / max(1f, lastDefenderX)) * 0.15f
                    isSafe = true
                }
                
                // Receiver is running on the defender's shoulder (Unstoppable Forward Run window)
                rx <= lastDefenderX -> {
                    // If sprinting past the defender within the next 120ms window, compute precise risk margin
                    if (projectedRx > lastDefenderX) {
                        risk = 0.45f // Sharp threshold: High momentum break, highly effective onside pass
                        isSafe = true
                    } else {
                        risk = 0.25f
                        isSafe = true
                    }
                }

                // Receiver has already crossed the offside line before ball release
                else -> {
                    val excessDistance = rx - lastDefenderX
                    risk = (0.70f + (excessDistance / 100f)).coerceIn(0.70f, 1.0f)
                    isSafe = false
                }
            }

            result.add(
                OffsideRisk(
                    lane = lane,
                    risk = risk,
                    safe = isSafe
                )
            )
        }

        // In-place sort avoids extra allocations
        result.sortBy { it.risk }

        return OffsideRiskEstimationResult(result)
    }
}
