package com.assistant.adapter.smartassist

object CentralOverloadDetectionEngine {

    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult
    ): CentralOverloadDetectionResult {

        occupancy.hashCode()
        pressure.hashCode()

        return CentralOverloadDetectionResult(
            centralControl = scene.fieldConfidence.coerceIn(0f,1f),
            overloaded = scene.playerCount >= 8,
            confidence = scene.confidence.coerceIn(0f,1f)
        )
    }
}
