package com.assistant.adapter.smartassist

object TacticalMapGenerationEngine {
    fun compute(
        scene: SceneSnapshot,
        occupancy: SpaceOccupancyResult,
        pressure: PressureFieldResult,
        teamShape: TeamShapeResult,
        defensiveLine: DefensiveLineResult,
        offensiveLine: OffensiveLineResult
    ): TacticalMapResult = TacticalMapResult()
}
