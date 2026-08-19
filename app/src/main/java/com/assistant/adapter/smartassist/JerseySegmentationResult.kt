package com.assistant.adapter.smartassist

data class JerseySegmentationResult(

    val userPixels: Int,

    val opponentPixels: Int,

    val goalkeeperPixels: Int,

    val confidence: Float

)
