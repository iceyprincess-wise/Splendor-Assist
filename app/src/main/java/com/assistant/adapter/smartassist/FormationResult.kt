package com.assistant.adapter.smartassist

data class FormationResult(
    val found: Boolean,
    val name: String = "Unknown",
    val confidence: Float = 0f
)
