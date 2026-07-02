package com.assistant.adapter.smartassist

object PressingRecognitionEngine {

    fun analyze(
        pressure: PressureFieldResult,
        compactness: DefensiveCompactnessResult,
        formation: FormationResult
    ): PressingRecognitionResult {

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val detected =
            formation.found &&
            compactness.compactness > 0.55f

        val confidence = (
            formation.confidence +
            compactness.confidence +
            pressureFactor
        ) / 3f

        return PressingRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
