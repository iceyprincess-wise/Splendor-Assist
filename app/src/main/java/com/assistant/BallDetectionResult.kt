package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class BallDetectionResult(

    val detected: Boolean,

    val x: Float,

    val y: Float,

    val confidence: Float,
    val observed: Boolean = detected

)

/* ========
BallDetector
======== */
