package com.assistant.adapter.smartassist

object SceneTracker {

    private var frameCounter = 0L

    private var trackedBallX = 0f
    private var trackedBallY = 0f
    private var trackedBallSpeed = 0f

    private var latest =
        SceneSnapshot()

    private val trackedPlayers = mutableListOf<TrackedPlayer>()

    private var trackedGoalkeeper: TrackedPlayer? = null

    private const val PLAYER_MEMORY_FRAMES = 15L
    private const val CONFIDENCE_DECAY = 0.05f

    fun update(
        state: GameStateSnapshot,
        players: PlayerDetectionResult
    ): SceneSnapshot {

        frameCounter++

        trackedPlayers.forEach {
            it.confidence = (it.confidence - CONFIDENCE_DECAY).coerceAtLeast(0f)
        }

        trackedBallX = state.ballX
        trackedBallY = state.ballY
        trackedBallSpeed = state.ballSpeed

        EntityAssociationEngine.associate(
            trackedPlayers,
            players.detections,
            frameCounter
        )

        if (state.goalkeeperDetected) {

            if (trackedGoalkeeper == null) {

                trackedGoalkeeper =
                    TrackedPlayer(
                        id = -1,

                        x = state.goalkeeperX,
                        y = state.goalkeeperY,

                        velocityX = 0f,
                        velocityY = 0f,

                        confidence = state.goalkeeperConfidence,

                        isUserTeam = true,
                        isGoalkeeper = true,

                        lastSeenFrame = frameCounter
                    )

            } else {

                val keeper = trackedGoalkeeper!!

                keeper.velocityX =
                    state.goalkeeperX - keeper.x

                keeper.velocityY =
                    state.goalkeeperY - keeper.y

                keeper.headingRadians =
                    kotlin.math.atan2(
                        keeper.velocityY,
                        keeper.velocityX
                    )

                keeper.x = state.goalkeeperX
                keeper.y = state.goalkeeperY

                keeper.confidence =
                    state.goalkeeperConfidence

                keeper.lastSeenFrame =
                    frameCounter
            }
        }

            if(trackedPlayers.isNotEmpty()){
            trackedPlayers.last().lastSeenFrame = frameCounter
            trackedPlayers.last().confidence = state.confidence.coerceIn(0f,1f)
        }

        trackedPlayers.removeAll {
            (frameCounter - it.lastSeenFrame) > PLAYER_MEMORY_FRAMES ||
            it.confidence <= 0f
        }

        latest =
            SceneSnapshot(
                frameNumber = frameCounter,

                ballVisible =
                    state.ballDetected,

                playerCount =
                    state.userPlayers +
                    state.opponentPlayers,

                userPlayers =
                    state.userPlayers,

                opponentPlayers =
                    state.opponentPlayers,

                trackedBallX = trackedBallX,

                trackedBallY = trackedBallY,

                trackedBallSpeed = trackedBallSpeed,

                trackedBallVisible = state.ballDetected,

                goalkeeperVisible =
                    trackedGoalkeeper != null,

                goalkeeperX =
                    trackedGoalkeeper?.x ?: 0f,

                goalkeeperY =
                    trackedGoalkeeper?.y ?: 0f,

                goalkeeperHeading =
                    trackedGoalkeeper?.headingRadians ?: 0f,

                goalDetected = state.goalDetected,
                goalLeftX = state.goalLeftX,
                goalRightX = state.goalRightX,
                goalTopY = state.goalTopY,
                goalBottomY = state.goalBottomY,
                goalConfidence = state.goalConfidence,

                touchLinesDetected = state.touchLinesDetected,
                penaltyAreaDetected = state.penaltyAreaDetected,
                goalAreaDetected = state.goalAreaDetected,
                centerCircleDetected = state.centerCircleDetected,
                fieldConfidence = state.fieldConfidence,

                trackedPlayers = trackedPlayers.toList(),

                confidence = state.confidence
            )

        return latest
    }

    fun current(): SceneSnapshot =
        latest
}
