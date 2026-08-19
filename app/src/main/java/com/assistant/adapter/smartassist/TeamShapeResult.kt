package com.assistant.adapter.smartassist

data class TeamShapeResult(
    val found: Boolean,
    val width: Float = 0f,
    val depth: Float = 0f,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val compactness: Float = 0f,
    val confidence: Float = 0f
)
