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

        val confidence=
            (0.60f + retention * 0.40f)
                .coerceAtMost(1f)

        return ReceiverEngagementResult(
            engagementBoost=
                1f + (retention * 0.30f),

            interceptionRisk=
                (1f-retention)
                    .coerceIn(0f,1f),

            confidence=confidence
        )
    }
}
