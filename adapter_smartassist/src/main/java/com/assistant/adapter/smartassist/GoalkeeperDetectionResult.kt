package com.assistant.adapter.smartassist

data class GoalkeeperDetectionResult(

    val detected: Boolean,

    val x: Float,

    val y: Float,

    val confidence: Float
)
