package com.assistant.adapter.smartassist

object OffensiveLineEngine {

    fun compute(
        scene: SceneSnapshot
    ): OffensiveLineResult {

        val attackers =
            scene.trackedPlayers.filter { it.isUserTeam }

        if (attackers.isEmpty()) {
            return OffensiveLineResult(found = false)
        }

        val minX = attackers.minOf { it.x }
        val maxX = attackers.maxOf { it.x }
        val averageX = attackers.map { it.x }.average().toFloat()
        val confidence =
            attackers.map { it.confidence }.average().toFloat()

        return OffensiveLineResult(
            found = true,
            averageX = averageX,
            minX = minX,
            maxX = maxX,
            playerCount = attackers.size,
            confidence = confidence
        )
    }
}
