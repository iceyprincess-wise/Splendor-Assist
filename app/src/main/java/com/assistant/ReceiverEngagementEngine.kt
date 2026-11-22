package com.assistant

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

        // R-4 (Phase R): the risk field is deleted — the only reader of a
        // ReceiverEngagementResult read engagementBoost + confidence and
        // never the risk value, so it was computed and discarded.
        return ReceiverEngagementResult(
            engagementBoost = engagementBoost,
            confidence = confidence
        )
    }
}
