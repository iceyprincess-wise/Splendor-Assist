package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class CrossingLane(
    val lane: PassingLane,
    val targetX: Float,
    val targetY: Float,
    val viable: Boolean,
    val confidence: Float
)

