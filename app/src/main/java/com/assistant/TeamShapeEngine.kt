package com.assistant

import kotlin.math.*
import kotlin.math.sqrt

object TeamShapeEngine {

    fun compute(
        scene: SceneSnapshot
    ): TeamShapeResult = computeFor(scene.trackedPlayers)

    /**
     * Midfield (queue item 5, batch midfield): the same shape math over
     * an explicit player list — VisionCore feeds the midfield band
     * players here for the midfield compactness input. compute(scene)
     * above delegates to this, so the whole-pitch behaviour is
     * unchanged (same list, same order, same arithmetic).
     */
    fun computeFor(
        players: List<TrackedPlayer>
    ): TeamShapeResult {

        if (players.isEmpty()) {
            return TeamShapeResult(found = false)
        }

        val minX = players.minOf { it.x }
        val maxX = players.maxOf { it.x }

        val minY = players.minOf { it.y }
        val maxY = players.maxOf { it.y }

        val width = maxX - minX
        val depth = maxY - minY

        // Match Float.average(): ordered Double accumulation and one Float cast.
        // Indexed traversal avoids iterator and mapped-list allocations.
        var sumConfidence = 0.0
        for (i in players.indices) {
            val player = players[i]
            sumConfidence += player.confidence.toDouble()
        }

        val compactness =
            sqrt(width * width + depth * depth)

        val confidence = (sumConfidence / players.size).toFloat()

        return TeamShapeResult(
            found = true,
            width = width,
            depth = depth,
            compactness = compactness,
            confidence = confidence
        )
    }
}
