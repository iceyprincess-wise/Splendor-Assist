package com.assistant.adapter.smartassist

object CentralOverloadDetectionEngine {
    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult
    ): CentralOverloadDetectionResult = CentralOverloadDetectionResult()
}
