package com.assistant.adapter.smartassist

object PossessionStyleRecognitionEngine {

    fun analyze(
        possession: BallPossessionResult,
        graph: PassingLaneGraph,
        pressure: PressureFieldResult
    ): PossessionStyleRecognitionResult {

        val detected =
            possession.hasPossession &&
            possession.possessionFrames > 30L

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val laneFactor =
            if (graph.lanes.isNotEmpty()) 1f else 0f

        val confidence = (
            possession.confidence +
            laneFactor +
            pressureFactor
        ) / 3f

        return PossessionStyleRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
