package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class WingOverloadDetectionResult(
    val leftWingAdvantage: Float = 0f,
    val rightWingAdvantage: Float = 0f,
    val overloaded: Boolean = false,
    val confidence: Float = 0f
)

/* ========
SharpTouchTimingEngine
======== */
