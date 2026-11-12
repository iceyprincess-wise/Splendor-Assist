package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ShootingLane(
    val shooter: TrackedPlayer,
    val targetX: Float,
    val targetY: Float,
    val distance: Float,
    val confidence: Float,
    val viable: Boolean
)

