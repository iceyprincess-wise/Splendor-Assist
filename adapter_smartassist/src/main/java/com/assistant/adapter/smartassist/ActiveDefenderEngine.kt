package com.assistant.adapter.smartassist

import kotlin.math.sqrt

object ActiveDefenderEngine {

    fun compute(
        scene: SceneSnapshot,
        attacker: ActiveAttackerResult
    ): ActiveDefenderResult {

        if (!attacker.found || attacker.attacker == null) {
            return ActiveDefenderResult(found = false)
        }

        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE

        scene.trackedPlayers.forEachIndexed { index, player ->

            if (index == attacker.attackerIndex) return@forEachIndexed

            if (player.isUserTeam == attacker.attacker.isUserTeam) {
                return@forEachIndexed
            }

            val dx = player.x - attacker.attacker.x
            val dy = player.y - attacker.attacker.y

            val distance = sqrt(dx * dx + dy * dy)

            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        return if (bestIndex >= 0) {
            ActiveDefenderResult(
                found = true,
                defender = scene.trackedPlayers[bestIndex],
                defenderIndex = bestIndex,
                distanceToAttacker = bestDistance,
                confidence = scene.trackedPlayers[bestIndex].confidence
            )
        } else {
            ActiveDefenderResult(found = false)
        }
    }
}
