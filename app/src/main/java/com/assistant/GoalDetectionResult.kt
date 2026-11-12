package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class GoalDetectionResult(

    val detected: Boolean,

    val leftX: Float,

    val rightX: Float,

    val topY: Float,

    val bottomY: Float,

    val confidence: Float

)

/* ========
GoalDetector
======== */
