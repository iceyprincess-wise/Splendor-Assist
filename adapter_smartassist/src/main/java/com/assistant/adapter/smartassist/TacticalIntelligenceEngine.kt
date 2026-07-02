package com.assistant.adapter.smartassist

object TacticalIntelligenceEngine {

    fun analyze(
        analytics: TacticalAnalyticsResult,
        behavior: TacticalBehaviorRecognitionResult,
        state: GameStateSnapshot
    ): TacticalIntelligenceResult {

        val confidence =
            ((analytics.confidence + behavior.confidence) * 0.5f)
                .coerceIn(0f, 1f)

        return TacticalIntelligenceResult(
            confidence = confidence
        )
    }
}

