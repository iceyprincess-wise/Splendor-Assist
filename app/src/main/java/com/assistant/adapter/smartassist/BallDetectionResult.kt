package com.assistant.adapter.smartassist

data class BallDetectionResult(

    val detected: Boolean,

    val x: Float,

    val y: Float,

    val radius: Float,

    val confidence: Float,

    val searchPixels: Int,

    val matchedPixels: Int

)
