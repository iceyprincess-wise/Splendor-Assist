package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class OnlineParameterAdaptationResult(
    val confidence: Float = 0f,
    val adaptationGain: Float = 0f,
    val tickSyncMultiplier: Float = 1.0f,
    val humanizedNoise: Float = 0.0f
)

/* ========
OpenSpaceDetectionEngine
======== */
