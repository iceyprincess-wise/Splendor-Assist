package com.assistant

data class PredictedRun(
    val player:TrackedPlayer,
    val predictedX:Float,
    val predictedY:Float,
    val confidence:Float
)

