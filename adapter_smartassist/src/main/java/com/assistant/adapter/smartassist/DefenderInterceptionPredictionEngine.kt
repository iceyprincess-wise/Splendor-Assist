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

    fun analyze(
        scene: SceneSnapshot,
        graph: PassingLaneGraph
    ): DefenderInterceptionPredictionAnalysis {

        val defenders =
            scene.trackedPlayers.filter {
                !it.isUserTeam
            }

        if (defenders.isEmpty()) {
            return DefenderInterceptionPredictionAnalysis()
        }

        val result = ArrayList<DefenderInterceptionPrediction>()

        graph.lanes.forEach { lane ->

            val midX =
                (lane.passer.x + lane.receiver.x) * 0.5f

            val midY =
                (lane.passer.y + lane.receiver.y) * 0.5f

            var nearest:TrackedPlayer?=null
            var best=Float.MAX_VALUE

            defenders.forEach { defender ->

                val d =
                    hypot(
                        (midX-defender.x).toDouble(),
                        (midY-defender.y).toDouble()
                    ).toFloat()

                if(d<best){
                    best=d
                    nearest=defender
                }
            }

            val defender=nearest!!

            val predictedX =
                defender.x + (defender.velocityX*0.35f)

            val predictedY =
                defender.y + (defender.velocityY*0.35f)

            val risk =
                (
                    (1f-(best/400f))*0.70f+
                    lane.pressure*0.30f
                ).coerceIn(0f,1f)

            result +=
                DefenderInterceptionPrediction(
                    lane=lane,
                    interceptionRisk=risk,
                    predictedInterceptX=predictedX,
                    predictedInterceptY=predictedY,
                    predictedIntercept=risk>=0.50f
                )
        }

        return DefenderInterceptionPredictionAnalysis(
            result.sortedByDescending{
                it.interceptionRisk
            }
        )
    }
}
