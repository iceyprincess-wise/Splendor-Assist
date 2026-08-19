package com.assistant.adapter.smartassist

data class CounterattackDetectionResult(
    val detected:Boolean=false,
    val confidence:Float=0f
)

object CounterattackDetectionEngine{

    fun analyze(
        scene:SceneSnapshot,
        teamShape:TeamShapeResult,
        offensiveLine:OffensiveLineResult
    ):CounterattackDetectionResult{

        val attackers=
            scene.trackedPlayers.count{
                it.isUserTeam
            }

        val confidence =
            (
                (attackers / 11f) +
                (if (teamShape.found) 0.15f else 0f) +
                (if (offensiveLine.found) 0.15f else 0f) +
                (teamShape.confidence * 0.05f) +
                (offensiveLine.confidence * 0.05f)
            ).coerceIn(0f,1f)

        return CounterattackDetectionResult(
            detected=confidence>=0.60f,
            confidence=confidence
        )
    }
}
