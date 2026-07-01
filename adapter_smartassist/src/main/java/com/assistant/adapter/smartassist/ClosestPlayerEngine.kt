package com.assistant.adapter.smartassist

import kotlin.math.sqrt

object ClosestPlayerEngine {

    fun compute(
        ball: BallDetectionResult,
        scene: SceneSnapshot
    ): ClosestPlayerResult {

        if (!ball.detected || scene.trackedPlayers.isEmpty()) {
            return ClosestPlayerResult(found = false)
        }

        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE

        scene.trackedPlayers.forEachIndexed { index, player ->

            val dx = player.x - ball.x
            val dy = player.y - ball.y

            val distance = sqrt(dx * dx + dy * dy)

            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        return if (bestIndex >= 0) {
            ClosestPlayerResult(
                found = true,
                index = bestIndex,
                distance = bestDistance,
                player = scene.trackedPlayers[bestIndex]
            )
        } else {
            ClosestPlayerResult(found = false)
        }
    }
}
