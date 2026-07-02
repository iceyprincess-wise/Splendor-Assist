package com.assistant.adapter.smartassist

object WingOverloadDetectionEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult
    ): WingOverloadDetectionResult {

        val overloaded = scene.playerCount >= 8

        val confidence = (
            scene.confidence +
            scene.fieldConfidence +
            if (pressure.rows>0 && occupancy.rows>0) 1f else 0f
        ) / 3f

        return WingOverloadDetectionResult(
            leftWingAdvantage = 0.5f,
            rightWingAdvantage = 0.5f,
            overloaded = overloaded,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
