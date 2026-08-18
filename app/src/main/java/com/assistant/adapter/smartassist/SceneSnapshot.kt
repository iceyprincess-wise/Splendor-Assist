package com.assistant.adapter.smartassist

data class SceneSnapshot(
    val frameNumber: Long = 0L,

    val timestamp: Long = System.currentTimeMillis(),

    val ballVisible: Boolean = false,

    val playerCount: Int = 0,

    val trackedPlayers: List<TrackedPlayer> = emptyList(),

    val userPlayers: Int = 0,

    val opponentPlayers: Int = 0,

    val trackedBallX: Float = 0f,

    val trackedBallY: Float = 0f,

    val trackedBallSpeed: Float = 0f,

    val trackedBallVisible: Boolean = false,

    val goalkeeperVisible: Boolean = false,

    val goalkeeperX: Float = 0f,

    val goalkeeperY: Float = 0f,

    val goalkeeperHeading: Float = 0f,

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
