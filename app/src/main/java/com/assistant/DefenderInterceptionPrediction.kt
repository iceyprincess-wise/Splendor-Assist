package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class DefenderInterceptionPrediction(
    val lane: PassingLane,
    val interceptionRisk: Float,
    val predictedInterceptX: Float,
    val predictedInterceptY: Float,
    val predictedIntercept: Boolean
)

