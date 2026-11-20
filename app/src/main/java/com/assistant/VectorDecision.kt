package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class VectorDecision(
    val shouldAct: Boolean,
    val actionType: ActionType,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long
)

