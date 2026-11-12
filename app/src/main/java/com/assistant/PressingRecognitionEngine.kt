package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object PressingRecognitionEngine {
    fun analyze(pressure: PressureFieldResult, compactness: DefensiveCompactnessResult, formation: FormationResult): PressingRecognitionResult {
        val measuredPressure = ObservedTactics.pressure(pressure) ?: return PressingRecognitionResult()
        if (!formation.found || !compactness.compactness.isFinite()) return PressingRecognitionResult()
        val confidence = (formation.confidence.coerceIn(0f,1f) + compactness.confidence.coerceIn(0f,1f) + measuredPressure) / 3f
        return PressingRecognitionResult(measuredPressure > 0.4f && compactness.compactness > 0.55f, confidence)
    }
}

/* ========
ReceiverRankingEngine
======== */
