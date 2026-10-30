data class OverloadPlaystyleResult(
    val mode: OverloadMode,
    val zone: OverloadZone,
    val overloadStrength: Float,
    val opponentOverload: Boolean,
    val switchPlayRecommended: Boolean,
    val exploitX: Float,
    val exploitY: Float,
    val confidence: Float
)

