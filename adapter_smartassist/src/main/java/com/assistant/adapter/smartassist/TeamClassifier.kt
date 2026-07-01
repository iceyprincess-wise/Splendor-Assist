package com.assistant.adapter.smartassist

object TeamClassifier {

    fun classify(
        scene: SceneSnapshot
    ): TeamClassificationResult {

        val user =
            scene.trackedPlayers.count { it.isUserTeam }

        val opponent =
            scene.trackedPlayers.count { !it.isUserTeam }

        return TeamClassificationResult(
            userPlayers = user,
            opponentPlayers = opponent,
            confidence =
                scene.trackedPlayers
                    .map { it.confidence }
                    .average()
                    .toFloat()
                    .takeIf { !it.isNaN() } ?: 0f
        )
    }
}
