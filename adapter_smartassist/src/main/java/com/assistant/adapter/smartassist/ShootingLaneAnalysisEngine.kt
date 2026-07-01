package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class ShootingLane(
    val shooter: TrackedPlayer,
    val targetX: Float,
    val targetY: Float,
    val distance: Float,
    val confidence: Float,
    val viable: Boolean
)

data class ShootingLaneAnalysis(
    val lanes: List<ShootingLane> = emptyList()
)

object ShootingLaneAnalysisEngine {

    fun analyze(
        scene: SceneSnapshot,
        graph: PassingLaneGraph
    ): ShootingLaneAnalysis {

        if (!scene.goalDetected) {
            return ShootingLaneAnalysis()
        }

        val goalCenterX =
            (scene.goalLeftX + scene.goalRightX) * 0.5f

        val goalCenterY =
            (scene.goalTopY + scene.goalBottomY) * 0.5f

        val result = ArrayList<ShootingLane>()

        graph.lanes.forEach { lane ->

            val shooter = lane.receiver

            val distance =
                hypot(
                    (goalCenterX - shooter.x).toDouble(),
                    (goalCenterY - shooter.y).toDouble()
                ).toFloat()

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure) *
                    (1f - (distance / 1500f))
                ).coerceIn(0f,1f)

            result += ShootingLane(
                shooter = shooter,
                targetX = goalCenterX,
                targetY = goalCenterY,
                distance = distance,
                confidence = confidence,
                viable = !lane.blocked && confidence >= 0.40f
            )
        }

        return ShootingLaneAnalysis(
            result.sortedByDescending {
                it.confidence
            }
        )
    }
}
