package com.assistant.adapter.smartassist

data class PlayerDetectionResult(
    val detected: Boolean,
    val playerCount: Int,
    val confidence: Float,
    val detections: List<PlayerDetection>
)
