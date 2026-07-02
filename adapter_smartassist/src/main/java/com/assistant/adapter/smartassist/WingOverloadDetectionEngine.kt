package com.assistant.adapter.smartassist

object WingOverloadDetectionEngine {
    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult
    ): WingOverloadDetectionResult = WingOverloadDetectionResult()
}
