data class VectorDecision(
    val shouldAct: Boolean,
    val priority: Int,
    val actionType: ActionType,
    val confidence: Float,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long
)

