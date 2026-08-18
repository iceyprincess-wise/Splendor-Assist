package com.assistant.adapter.smartassist

data class GoalDetectionResult(

    val detected: Boolean,

    val leftX: Float,

    val rightX: Float,

    val topY: Float,

    val bottomY: Float,

    val confidence: Float

)
