package com.assistant.adapter.smartassist

object DefensiveLineEngine {

    fun compute(
        scene: SceneSnapshot
    ): DefensiveLineResult {

        val defenders =
            scene.trackedPlayers.filter { !it.isUserTeam }

        if (defenders.isEmpty()) {
            return DefensiveLineResult(found = false)
        }

        val minX = defenders.minOf { it.x }
        val maxX = defenders.maxOf { it.x }
        val averageX = defenders.map { it.x }.average().toFloat()
        val confidence =
            defenders.map { it.confidence }.average().toFloat()

        return DefensiveLineResult(
            found = true,
            averageX = averageX,
            minX = minX,
            maxX = maxX,
            playerCount = defenders.size,
            confidence = confidence
        )
    }
}
