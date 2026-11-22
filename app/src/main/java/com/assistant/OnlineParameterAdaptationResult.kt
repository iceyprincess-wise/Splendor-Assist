package com.assistant

data class OnlineParameterAdaptationResult(
    val confidence: Float = 0f,
    val adaptationGain: Float = 0f,
    val tickSyncMultiplier: Float = 1.0f
)
