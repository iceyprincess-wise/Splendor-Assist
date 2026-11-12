package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class BallCandidate(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val pixelCount: Int,
    val brightness: Float,
    val score: Float
)

/* ========
BallCandidateEngine
======== */
