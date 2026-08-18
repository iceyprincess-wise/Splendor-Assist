package com.assistant.adapter.smartassist

data class CentralOverloadDetectionResult(
    val centralControl: Float = 0f,
    val overloaded: Boolean = false,
    val confidence: Float = 0f
)
