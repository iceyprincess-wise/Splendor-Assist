package com.assistant.adapter.smartassist

data class OffensiveLineResult(
    val found: Boolean,
    val averageX: Float = 0f,
    val minX: Float = 0f,
    val maxX: Float = 0f,
    val playerCount: Int = 0,
    val confidence: Float = 0f
)
