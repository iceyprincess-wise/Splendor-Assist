package com.assistant.adapter.smartassist

object DefensiveCompactnessEngine {

    fun compute(
        scene: SceneSnapshot,
        defensiveLine: DefensiveLineResult,
        teamShape: TeamShapeResult
    ): DefensiveCompactnessResult {

        val confidence = (
            scene.fieldConfidence +
            defensiveLine.confidence +
            teamShape.confidence
        ) / 3f

        return DefensiveCompactnessResult(
            horizontalCompactness = teamShape.width.coerceIn(0f,1f),
            verticalCompactness = teamShape.depth.coerceIn(0f,1f),
            compactness = teamShape.compactness.coerceIn(0f,1f),
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
