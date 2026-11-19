package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ThroughBallLane(
    val lane: PassingLane,
    val viable: Boolean,
    val leadDistance: Float,
    val confidence: Float,
    // B9: direction-aware lead VECTOR (px) — the scalar leadDistance
    // resolved along the lane / receiver-velocity direction. A lead of
    // (0,0) means "no direction could be established" (degenerate lane);
    // consumers must not fall back to a −Y offset.
    val leadX: Float = 0f,
    val leadY: Float = 0f
)

