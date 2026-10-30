data class PassingAssistResult(
    val correctedX: Float,
    val correctedY: Float,
    val interceptionRisk: Float,
    val requiresEscapeVector: Boolean = false
)

