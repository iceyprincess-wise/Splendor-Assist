package com.assistant.adapter.smartassist

data class DecisionResult(
    val mode: Int,
    val strength: Int,
    val confidence: Float,
    val priority: Int
)
