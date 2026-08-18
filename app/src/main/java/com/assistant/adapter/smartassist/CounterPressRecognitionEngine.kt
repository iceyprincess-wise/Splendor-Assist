package com.assistant.adapter.smartassist

object CounterPressRecognitionEngine {

    fun analyze(
        scene: SceneSnapshot,
        possession: BallPossessionResult,
        pressure: PressureFieldResult
    ): CounterPressRecognitionResult {

        val detected =
            possession.hasPossession &&
            possession.possessionChanged

        val pressureFactor =
            if (pressure.rows>0 && pressure.columns>0) 1f else 0f

        val confidence = (
            scene.confidence +
            possession.confidence +
            pressureFactor
        ) / 3f

        return CounterPressRecognitionResult(
            detected = detected,
            confidence = confidence.coerceIn(0f,1f)
        )
    }
}
