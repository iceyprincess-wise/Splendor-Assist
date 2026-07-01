package com.assistant.adapter.smartassist

data class GameStateSnapshot(
    val timestamp: Long = System.currentTimeMillis(),

    val ballDetected: Boolean = false,
    val playerDetected: Boolean = false,
    val goalkeeperDetected: Boolean = false,

    val goalkeeperX: Float = 0f,
    val goalkeeperY: Float = 0f,
    val goalkeeperConfidence: Float = 0f,

    val userPlayers: Int = 0,
    val opponentPlayers: Int = 0,

    val ballX: Float = 0f,
    val ballY: Float = 0f,

    val ballVelocityX: Float = 0f,
    val ballVelocityY: Float = 0f,
    val ballSpeed: Float = 0f,
    val ballDirection: Float = 0f,

    val goalDetected: Boolean = false,
    val goalLeftX: Float = 0f,
    val goalRightX: Float = 0f,
    val goalTopY: Float = 0f,
    val goalBottomY: Float = 0f,
    val goalConfidence: Float = 0f,

    val touchLinesDetected: Boolean = false,
    val penaltyAreaDetected: Boolean = false,
    val goalAreaDetected: Boolean = false,
    val centerCircleDetected: Boolean = false,
    val fieldConfidence: Float = 0f,

    val confidence: Float = 0f
)
