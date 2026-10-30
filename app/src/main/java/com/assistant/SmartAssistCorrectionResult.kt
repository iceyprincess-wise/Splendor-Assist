data class SmartAssistCorrectionResult(
    val correctedX: Float,
    val correctedY: Float,
    val correctionStrength: Float,
    val correctionType: CorrectionType,
    val applied: Boolean
)

