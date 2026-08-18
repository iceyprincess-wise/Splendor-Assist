package com.assistant.adapter.smartassist

import kotlin.math.hypot
import kotlin.random.Random

data class DefensiveContainmentResult(
    val movements: List<Pair<Float, Float>>
)

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

        if (ballY >= activationThreshold) {
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

            val centralPenaltySpotX = safeWidth * 0.5f

            defenders.forEachIndexed { index, defender ->
                val optimalX = (safeWidth / (defenders.size + 1)) * (index + 1)

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

                // Inject dynamic fractional math jitter to scramble linear tracking data patterns
                val humanizationNoiseX = Random.nextFloat() * 1.5f - 0.75f // +/- 0.75 pixel horizontal wobble
                val humanizationNoiseY = Random.nextFloat() * 1.8f - 0.90f // Staggers defensive line heights uniquely

                val lerpFactor = 0.35f + (hazardLevel * 0.30f)

                val targetX = (
                    defender.first +
                        ((optimalX - defender.first) * lerpFactor) +
                        horizontalBias + humanizationNoiseX
                    ).coerceIn(0f, safeWidth)

                val defenderLineY = baseTargetLineY + humanizationNoiseY

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
