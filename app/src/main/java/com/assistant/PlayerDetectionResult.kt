package com.assistant

data class PlayerDetectionResult(
    val detected: Boolean,
    val confidence: Float,
    val detections: List<PlayerDetection>
)
