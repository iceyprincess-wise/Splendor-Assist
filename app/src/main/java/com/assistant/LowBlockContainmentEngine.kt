package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object LowBlockContainmentEngine {

    /**
     * Deep low-block defensive cover and spatial squeeze.
     */
    fun applyLowBlockContainment(
        ballX: Float,
        ballY: Float,
        pitchWidth: Float,
        pitchHeight: Float,
        defenders: List<Pair<Float, Float>>
    ): DefensiveContainmentResult {
        if (defenders.isEmpty()) {
            return DefensiveContainmentResult(
                movements = emptyList()
            )
        }

        val safeWidth = pitchWidth.coerceAtLeast(1f)
        val safeHeight = pitchHeight.coerceAtLeast(1f)
        val updated = ArrayList<Pair<Float, Float>>(defenders.size)

        val activationThreshold = safeHeight * 0.70f

        // No68-D-D20: when the ball is not deep the old code returned an
        // EMPTY list — a consumer expecting one movement per defender got
        // zero, so the shape contract (movements.size == defenders.size)
        // broke exactly when the line should be holding its shape.
        // Inactive now means "hold position": return the defenders where
        // they already are.
        if (ballY < activationThreshold) {
            return DefensiveContainmentResult(movements = defenders.toList())
        }

        run {
            val ballDepthProgress = ((ballY - activationThreshold) / (safeHeight - activationThreshold))
                .coerceIn(0f, 1f)

            val baseTargetLineY = safeHeight * (0.82f + (ballDepthProgress * 0.07f))

            var closestDefenderIndex = -1
            var minDistanceToBall = Float.MAX_VALUE

            defenders.forEachIndexed { index, defender ->
                val distanceToBall = hypot(
                    (defender.first - ballX).toDouble(),
                    (defender.second - ballY).toDouble()
                ).toFloat()

                if (distanceToBall < minDistanceToBall) {
                    minDistanceToBall = distanceToBall
                    closestDefenderIndex = index
                }
            }

            // No68-D-D19: slots were assigned by INPUT index order, so a
            // defender listed first always took the leftmost slot even
            // when standing on the right — the line crossed itself. Slots
            // now follow each defender's RANK in current-X order (ties
            // break by input index, so the order is deterministic), while
            // movements are still returned in input order so a consumer
            // matching by index gets the right defender.
            val rankOf = IntArray(defenders.size) { index ->
                defenders.count { other ->
                    other.first < defenders[index].first
                } + defenders.subList(0, index).count { it.first == defenders[index].first }
            }

            val centralPenaltySpotX = safeWidth * 0.5f

            defenders.forEachIndexed { index, defender ->
                val optimalX = (safeWidth / (defenders.size + 1)) * (rankOf[index] + 1)

                val hazardLevel = ((ballY - (safeHeight * 0.80f)) / (safeHeight * 0.20f))
                    .coerceIn(0f, 1f)

                val isClosestToBall = index == closestDefenderIndex

                val horizontalBias = if (isClosestToBall) {
                    val challengerShiftRange = safeWidth * (0.18f + (hazardLevel * 0.10f))
                    ((ballX / safeWidth) - 0.5f) * challengerShiftRange
                } else {
                    val coverShiftRange = safeWidth * (0.14f + (hazardLevel * 0.06f))
                    val ballBias = ((ballX / safeWidth) - 0.5f) * coverShiftRange
                    val squeezeCenterBias = (centralPenaltySpotX - optimalX) * (0.25f + (hazardLevel * 0.15f))
                    ballBias + squeezeCenterBias
                }

                val lerpFactor = 0.35f + (hazardLevel * 0.30f)

                val targetX = (
                    defender.first +
                        ((optimalX - defender.first) * lerpFactor) +
                        horizontalBias
                    ).coerceIn(0f, safeWidth)

                val defenderLineY = baseTargetLineY

                updated.add(
                    Pair(
                        targetX,
                        defenderLineY.coerceIn(0f, safeHeight)
                    )
                )
            }
        }

        return DefensiveContainmentResult(
            movements = updated
        )
    }
}

/* ========
LowLatencyNetworkThread
======== */
