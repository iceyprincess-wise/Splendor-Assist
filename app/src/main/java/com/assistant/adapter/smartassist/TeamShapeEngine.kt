package com.assistant.adapter.smartassist

import kotlin.math.sqrt

object TeamShapeEngine {

    fun compute(
        scene: SceneSnapshot
    ): TeamShapeResult {

        if (scene.trackedPlayers.isEmpty()) {
            return TeamShapeResult(found = false)
        }

        val minX = scene.trackedPlayers.minOf { it.x }
        val maxX = scene.trackedPlayers.maxOf { it.x }

        val minY = scene.trackedPlayers.minOf { it.y }
        val maxY = scene.trackedPlayers.maxOf { it.y }

        val width = maxX - minX
        val depth = maxY - minY

        val centerX =
            scene.trackedPlayers.map { it.x }.average().toFloat()

        val centerY =
            scene.trackedPlayers.map { it.y }.average().toFloat()

        val compactness =
            sqrt(width * width + depth * depth)

        val confidence =
            scene.trackedPlayers
                .map { it.confidence }
                .average()
                .toFloat()

        return TeamShapeResult(
            found = true,
            width = width,
            depth = depth,
            centerX = centerX,
            centerY = centerY,
            compactness = compactness,
            confidence = confidence
        )
    }
}
