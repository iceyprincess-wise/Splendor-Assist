package com.assistant.adapter.smartassist

import kotlin.math.sqrt

object DefensiveCompactnessEngine {

    private const val SCREEN_W = 1650f
    private const val SCREEN_H = 720f
    private val MAX_SPREAD = sqrt(SCREEN_W*SCREEN_W + SCREEN_H*SCREEN_H)

    fun compute(
        scene: SceneSnapshot,
        defensiveLine: DefensiveLineResult,
        teamShape: TeamShapeResult
    ): DefensiveCompactnessResult {
        val confidence = (scene.fieldConfidence + defensiveLine.confidence + teamShape.confidence) / 3f
        // FIX: raw pixel values normalized against screen dims before coerceIn.
        // Old code: coerceIn(0f,1f) on e.g. 800f -> always 1.0.
        return DefensiveCompactnessResult(
            horizontalCompactness = (teamShape.width      / SCREEN_W  ).coerceIn(0f,1f),
            verticalCompactness   = (teamShape.depth      / SCREEN_H  ).coerceIn(0f,1f),
            compactness           = (teamShape.compactness / MAX_SPREAD).coerceIn(0f,1f),
            confidence            = confidence.coerceIn(0f,1f)
        )
    }
}
