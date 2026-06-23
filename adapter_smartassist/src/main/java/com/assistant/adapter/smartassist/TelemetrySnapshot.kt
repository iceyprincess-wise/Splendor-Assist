package com.assistant.adapter.smartassist

data class TelemetrySnapshot(
    val timestamp: Long = System.currentTimeMillis(),

    val playerVelocity: Float = 0f,
    val opponentDistance: Float = Float.MAX_VALUE,

    val ballX: Float = 0f,
    val ballY: Float = 0f,
    val ballVelocityX: Float = 0f,
    val ballVelocityY: Float = 0f,

    val goalkeeperX: Float = 0f,
    val goalkeeperY: Float = 0f,

    val confidence: Float = 0f
)
