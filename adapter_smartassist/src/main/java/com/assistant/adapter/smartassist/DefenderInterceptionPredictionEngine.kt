package com.assistant.adapter.smartassist

import kotlin.math.hypot
import kotlin.random.Random

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

    // Physical constants tuned for professional, low-latency companion execution
    private const val BASE_ESTIMATED_BALL_SPEED = 1450.0f    // Average pass velocity in pixels/units per second
    private const val BASE_DEFENDER_MAX_SPEED = 420.0f      // Average defender sprint velocity
    private const val BASE_DEFENDER_REACTION_LATENCY = 0.12f // Reaction delay/inertia before sprinting
    private const val SAFE_PASS_BUFFER_SEC = 0.08f           // Safe buffer time required to guarantee completion

    fun analyze(
        scene: SceneSnapshot,
        graph: PassingLaneGraph
    ): DefenderInterceptionPredictionAnalysis {

        val defenders = scene.trackedPlayers.filter { !it.isUserTeam }
        if (defenders.isEmpty() || graph.lanes.isEmpty()) {
            return DefenderInterceptionPredictionAnalysis()
        }

        val result = ArrayList<DefenderInterceptionPrediction>()

        // Dynamically perturb environmental metrics per analysis frame to mask computational constants
        val ballSpeedFuzz = BASE_ESTIMATED_BALL_SPEED + (Random.nextFloat() * 30.0f - 15.0f) // +/- 15 units variance
        val defSpeedFuzz = BASE_DEFENDER_MAX_SPEED + (Random.nextFloat() * 10.0f - 5.0f)
        val reactionLatencyFuzz = BASE_DEFENDER_REACTION_LATENCY + (Random.nextFloat() * 0.01f - 0.005f)

        graph.lanes.forEach { lane ->
            val passer = lane.passer
            val receiver = lane.receiver

            // 1. Passing Lane Vector definitions
            val laneDx = receiver.x - passer.x
            val laneDy = receiver.y - passer.y
            val laneDistance = hypot(laneDx.toDouble(), laneDy.toDouble()).toFloat()

            if (laneDistance <= 0f) return@forEach

            // 2. Identify the single highest-threat defender using Point-to-Segment Math
            var primaryThreatDefender: TrackedPlayer? = null
            var lowestTimeToIntercept = Float.MAX_VALUE
            var bestInterceptX = (passer.x + receiver.x) * 0.5f
            var bestInterceptY = (passer.y + receiver.y) * 0.5f
            var maximumCalculatedRisk = 0.0f

            defenders.forEach { defender ->
                // Vector from Passer to Defender
                val pdX = defender.x - passer.x
                val pdY = defender.y - passer.y

                // Project defender's position onto the passing lane segment to find the closest point (C)
                val dotProduct = (pdX * laneDx) + (pdY * laneDy)
                val projectionFactor = (dotProduct / (laneDistance * laneDistance)).coerceIn(0f, 1f)

                // Intercept Candidate Point (Closest approach on the passing path)
                val closestPointX = passer.x + (projectionFactor * laneDx)
                val closestPointY = passer.y + (projectionFactor * laneDy)

                // Distances involved
                val ballTravelDistance = projectionFactor * laneDistance
                val defenderDistanceToPath = hypot(
                    (closestPointX - defender.x).toDouble(),
                    (closestPointY - defender.y).toDouble()
                ).toFloat()

                // Calculate time variables (Seconds)
                val timeForBallToReachC = ballTravelDistance / ballSpeedFuzz

                // Defender capability projection (accounting for current velocity direction alignment)
                val defVelX = defender.velocityX
                val defVelY = defender.velocityY
                val toInterceptX = closestPointX - defender.x
                val toInterceptY = closestPointY - defender.y
                val distToIntercept = hypot(toInterceptX.toDouble(), toInterceptY.toDouble()).toFloat()

                // Calculate alignment factor (dot product of current velocity and vector to closest point)
                var directionAlignmentBonus = 0f
                if (distToIntercept > 0f) {
                    val velMagnitude = hypot(defVelX.toDouble(), defVelY.toDouble()).toFloat()
                    if (velMagnitude > 10f) {
                        val alignment = ((defVelX * toInterceptX) + (defVelY * toInterceptY)) / (velMagnitude * distToIntercept)
                        directionAlignmentBonus = (alignment * 0.15f).coerceIn(-0.1f, 0.2f) // Boost if already running towards ball path
                    }
                }

                // Estimated Time-to-Intercept for this defender (accounting for startup inertia)
                val defenderTimeToC = (defenderDistanceToPath / defSpeedFuzz) +
                                      reactionLatencyFuzz - directionAlignmentBonus

                // Interception window threat scoring
                val timeDifference = timeForBallToReachC - defenderTimeToC

                // Continuous mathematical curve for risk estimation
                val calculatedRisk = when {
                    timeDifference >= SAFE_PASS_BUFFER_SEC -> {
                        // Defender intercepts before or exactly as the ball arrives
                        1.0f
                    }
                    timeDifference < -0.8f -> {
                        // Defender cannot possibly close the gap
                        0.0f
                    }
                    else -> {
                        // Linear interpolation of risk based on the time margin
                        ((timeDifference + 0.8f) / (SAFE_PASS_BUFFER_SEC + 0.8f)).coerceIn(0.0f, 1.0f)
                    }
                }

                // Blend in pressure and line congestion to make threat detection completely bulletproof
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

            // 3. Extrapolate future intercept coordinates if defender has high risk
            val finalDefender = primaryThreatDefender!!
            var finalInterceptX: Float
            var finalInterceptY: Float

            // Inject organic float coordinate fuzzing to prevent absolute identical linear signatures
            val subPixelJitterX = Random.nextFloat() * 0.8f - 0.4f // +/- 0.4 units coordinate wobble
            val subPixelJitterY = Random.nextFloat() * 0.8f - 0.4f

            if (maximumCalculatedRisk > 0.40f) {
                // Highly realistic prediction point offset by defender's current velocity/momentum
                finalInterceptX = bestInterceptX + (finalDefender.velocityX * 0.25f) + subPixelJitterX
                finalInterceptY = bestInterceptY + (finalDefender.velocityY * 0.25f) + subPixelJitterY
            } else {
                // If risk is low, fall back to default mathematical projection midpoint of closest threat
                finalInterceptX = bestInterceptX + subPixelJitterX
                finalInterceptY = bestInterceptY + subPixelJitterY
            }

            result += DefenderInterceptionPrediction(
                lane = lane,
                interceptionRisk = maximumCalculatedRisk,
                predictedInterceptX = finalInterceptX,
                predictedInterceptY = finalInterceptY,
                predictedIntercept = maximumCalculatedRisk >= 0.50f
            )
        }

        return DefenderInterceptionPredictionAnalysis(
            lanes = result.sortedByDescending { it.interceptionRisk }
        )
    }
}
