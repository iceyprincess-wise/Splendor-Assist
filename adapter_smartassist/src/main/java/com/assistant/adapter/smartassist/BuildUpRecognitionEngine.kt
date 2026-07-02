package com.assistant.adapter.smartassist

object BuildUpRecognitionEngine {

    fun analyze(
        formation: FormationResult,
        teamShape: TeamShapeResult,
        graph: PassingLaneGraph
    ): BuildUpRecognitionResult {

        val detected = formation.found && graph.lanes.isNotEmpty()

        val confidence = (
            formation.confidence +
            teamShape.confidence +
            if (graph.lanes.isNotEmpty()) 1f else 0f
        ) / 3f

        return BuildUpRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
