package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class DefenderInterceptionPrediction(
    val lane: PassingLane,
    val interceptionRisk: Float,
    val predictedInterceptX: Float,
    val predictedInterceptY: Float,
    val predictedIntercept: Boolean
)

data class DefenderInterceptionPredictionAnalysis(
    val lanes: List<DefenderInterceptionPrediction> = emptyList()
)

object DefenderInterceptionPredictionEngine {

    companion object {
        // Physical constants tuned for professional, low-latency companion execution
        const val BASE_ESTIMATED_BALL_SPEED = 1450.0f    // Average pass velocity in pixels/units per second
        const val BASE_DEFENDER_MAX_SPEED = 420.0f      // Average defender sprint velocity
        const val BASE_DEFENDER_REACTION_LATENCY = 0.12f // Reaction delay/inertia before sprinting
        const val SAFE_PASS_BUFFER_SEC = 0.08f           // Safe buffer time required to guarantee completion
    }

    fun analyze(
        scene: SceneSnapshot,
        graph: PassingLaneGraph
    ): DefenderInterceptionPredictionAnalysis {

        if (scene.trackedPlayers.isEmpty() || graph.lanes.isEmpty()) {
            return DefenderInterceptionPredictionAnalysis(emptyList())
        }

        // 1. Zero-Allocation Strategy: Pre-allocate precise capacity to stop GC thrashing at 60FPS
        val result = ArrayList<DefenderInterceptionPrediction>(graph.lanes.size)
        val playersSize = scene.trackedPlayers.size

        // 2. Index loops to avoid iterator object creation
        for (i in 0 until graph.lanes.size) {
            val lane = graph.lanes[i]
            val passer = lane.passer
            val receiver = lane.receiver

            val laneDx = receiver.x - passer.x
            val laneDy = receiver.y - passer.y
            val laneDistance = hypot(laneDx.toDouble(), laneDy.toDouble()).toFloat()

            if (laneDistance <= 0f) continue

            var primaryThreatDefender: TrackedPlayer? = null
            var lowestTimeToIntercept = Float.MAX_VALUE
            var bestInterceptX = (passer.x + receiver.x) * 0.5f
            var bestInterceptY = (passer.y + receiver.y) * 0.5f
            var maximumCalculatedRisk = 0.0f

            for (j in 0 until playersSize) {
                val defender = scene.trackedPlayers[j]
                
                // Inline filter: replaces scene.trackedPlayers.filter { !it.isUserTeam } (saves 1 allocation per frame)
                if (defender.isUserTeam) continue

                val pdX = defender.x - passer.x
                val pdY = defender.y - passer.y

                val dotProduct = (pdX * laneDx) + (pdY * laneDy)
                val projectionFactor = (dotProduct / (laneDistance * laneDistance)).coerceIn(0f, 1f)

                val closestPointX = passer.x + (projectionFactor * laneDx)
                val closestPointY = passer.y + (projectionFactor * laneDy)

                val ballTravelDistance = projectionFactor * laneDistance
                val defenderDistanceToPath = hypot(
                    (closestPointX - defender.x).toDouble(),
                    (closestPointY - defender.y).toDouble()
                ).toFloat()

                val timeForBallToReachC = ballTravelDistance / BASE_ESTIMATED_BALL_SPEED

                val defVelX = defender.velocityX
                val defVelY = defender.velocityY
                val toInterceptX = closestPointX - defender.x
                val toInterceptY = closestPointY - defender.y
                val distToIntercept = hypot(toInterceptX.toDouble(), toInterceptY.toDouble()).toFloat()

                var directionAlignmentBonus = 0f
                if (distToIntercept > 0f) {
                    val velMagnitude = hypot(defVelX.toDouble(), defVelY.toDouble()).toFloat()
                    if (velMagnitude > 10f) {
                        val alignment = ((defVelX * toInterceptX) + (defVelY * toInterceptY)) / (velMagnitude * distToIntercept)
                        directionAlignmentBonus = (alignment * 0.15f).coerceIn(-0.1f, 0.2f)
                    }
                }

                val defenderTimeToC = (defenderDistanceToPath / BASE_DEFENDER_MAX_SPEED) +
                                      BASE_DEFENDER_REACTION_LATENCY - directionAlignmentBonus

                val timeDifference = timeForBallToReachC - defenderTimeToC

                val calculatedRisk = when {
                    timeDifference >= SAFE_PASS_BUFFER_SEC -> 1.0f
                    timeDifference < -0.8f -> 0.0f
                    else -> ((timeDifference + 0.8f) / (SAFE_PASS_BUFFER_SEC + 0.8f)).coerceIn(0.0f, 1.0f)
                }

                val blendedRisk = (calculatedRisk * 0.75f + lane.pressure * 0.25f).coerceIn(0f, 1f)

                if (defenderTimeToC < lowestTimeToIntercept) {
                    lowestTimeToIntercept = defenderTimeToC
                    primaryThreatDefender = defender
                    bestInterceptX = closestPointX
                    bestInterceptY = closestPointY
                }
                if (blendedRisk > maximumCalculatedRisk) {
                    maximumCalculatedRisk = blendedRisk
                }
            }

            if (primaryThreatDefender != null) {
                val finalDefender = primaryThreatDefender
                val finalInterceptX: Float
                val finalInterceptY: Float

                // Deterministic projection replaces Random fuzzing for 100% predictable AI logic
                if (maximumCalculatedRisk > 0.40f) {
                    finalInterceptX = bestInterceptX + (finalDefender.velocityX * 0.25f)
                    finalInterceptY = bestInterceptY + (finalDefender.velocityY * 0.25f)
                } else {
                    finalInterceptX = bestInterceptX
                    finalInterceptY = bestInterceptY
                }

                result.add(
                    DefenderInterceptionPrediction(
                        lane = lane,
                        interceptionRisk = maximumCalculatedRisk,
                        predictedInterceptX = finalInterceptX,
                        predictedInterceptY = finalInterceptY,
                        predictedIntercept = maximumCalculatedRisk >= 0.50f
                    )
                )
            }
        }

        // In-place sort avoids creating a third List allocation
        result.sortByDescending { it.interceptionRisk }
        return DefenderInterceptionPredictionAnalysis(result)
    }
}
