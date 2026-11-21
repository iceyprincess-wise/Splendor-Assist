package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class BallCandidate(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val pixelCount: Int,
    val score: Float
)

/* ========
BallCandidateEngine
======== */
