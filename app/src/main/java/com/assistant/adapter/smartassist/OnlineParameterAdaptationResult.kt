package com.assistant.adapter.smartassist

data class OnlineParameterAdaptationResult(
    val confidence: Float = 0f,
    val adaptationGain: Float = 0f,
    val tickSyncMultiplier: Float = 1.0f,
    val humanizedNoise: Float = 0.0f
)
