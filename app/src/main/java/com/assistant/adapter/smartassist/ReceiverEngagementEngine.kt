package com.assistant.adapter.smartassist

data class ReceiverEngagementResult(
    val engagementBoost:Float,
    val interceptionRisk:Float,
    val confidence:Float
)

object ReceiverEngagementEngine {

    fun evaluate(
        distance:Float,
        retention:Float
    ):ReceiverEngagementResult {

        val distanceFactor =
            (distance.coerceIn(0f,1000f) / 1000f)

        val confidence =
            (
                0.45f +
                retention * 0.35f +
                (1f - distanceFactor) * 0.20f
            ).coerceIn(0f,1f)

        val engagementBoost =
            1f +
            (retention * 0.70f) +
            ((1f - distanceFactor) * 0.30f)

        val interceptionRisk =
            (
                (1f - retention) * 0.70f +
                distanceFactor * 0.30f
            ).coerceIn(0f,1f)

        return ReceiverEngagementResult(
            engagementBoost = engagementBoost,
            interceptionRisk = interceptionRisk,
            confidence = confidence
        )
    }
}
