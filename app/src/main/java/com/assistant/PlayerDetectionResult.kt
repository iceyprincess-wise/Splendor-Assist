package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class PlayerDetectionResult(
    val detected: Boolean,
    val playerCount: Int,
    val confidence: Float,
    val detections: List<PlayerDetection>
)

/* ========
PlayerTendencyLearningEngine
======== */
