package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class PassingLane(
    val passer: TrackedPlayer,
    val receiver: TrackedPlayer,
    val distance: Float,
    val pressure: Float,
    val blocked: Boolean,
    val score: Float
)

/* ========
PlayerOverlay
======== */
