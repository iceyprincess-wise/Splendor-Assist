package com.assistant.adapter.smartassist

object ActiveAttackerEngine {

    fun compute(
        scene: SceneSnapshot,
        possession: BallPossessionResult
    ): ActiveAttackerResult {

        if (!possession.hasPossession) {
            return ActiveAttackerResult(found = false)
        }

        val index = possession.ownerIndex

        if (index !in scene.trackedPlayers.indices) {
            return ActiveAttackerResult(found = false)
        }

        val player = scene.trackedPlayers[index]

        return ActiveAttackerResult(
            found = true,
            attacker = player,
            attackerIndex = index,
            confidence = possession.confidence
        )
    }
}
