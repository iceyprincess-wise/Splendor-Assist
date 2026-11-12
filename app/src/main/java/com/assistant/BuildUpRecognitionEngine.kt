package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object BuildUpRecognitionEngine {
    fun analyze(formation: FormationResult, teamShape: TeamShapeResult, graph: PassingLaneGraph): BuildUpRecognitionResult {
        val laneQuality = ObservedTactics.laneQuality(graph)
        if (!formation.found || !teamShape.found || laneQuality <= 0f) return BuildUpRecognitionResult()
        return BuildUpRecognitionResult(laneQuality >= 0.5f,
            (formation.confidence.coerceIn(0f,1f) + teamShape.confidence.coerceIn(0f,1f) + laneQuality) / 3f)
    }
}

/* ========
BuildUpRecognitionResult
======== */
