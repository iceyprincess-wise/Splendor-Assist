object SceneTracker {

    private var frameCounter = 0L

    private var trackedBallX = 0f
    private var trackedBallY = 0f
    private var trackedBallSpeed = 0f

    private var latest =
        SceneSnapshot()

    private val trackedPlayers = mutableListOf<TrackedPlayer>()

    private val mutationLock = Any()

    private var trackedGoalkeeper: TrackedPlayer? = null

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val PLAYER_MEMORY_FRAMES: Long
        get() = 15L
    private val MAX_TRACKS: Int
        get() = 30

    private const val CONFIDENCE_DECAY = 0.05f

    fun update(
        state: GameStateSnapshot,
        players: PlayerDetectionResult
    ): SceneSnapshot = synchronized(mutationLock) {

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

        // prune the dead: unseen too long or fully faded
        val memory = PLAYER_MEMORY_FRAMES
        trackedPlayers.removeAll {
            (frameCounter - it.lastSeenFrame) > memory ||
            it.confidence <= 0f
        }

        // sanity cap: keep the freshest/strongest tracks only, so crowd-pixel
        // noise cannot inflate the count past what a real pitch can hold
        val cap = if (MAX_TRACKS < 1) 1 else MAX_TRACKS
        if (trackedPlayers.size > cap) {
            trackedPlayers.sortWith(
                compareByDescending<TrackedPlayer> { it.lastSeenFrame }
                    .thenByDescending { it.confidence }
            )
            while (trackedPlayers.size > cap) {
                trackedPlayers.removeAt(trackedPlayers.size - 1)
            }
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

        latest
    }

    fun current(): SceneSnapshot =
        latest
}
/* ======
SceneTracker Anchor
====== */

/* ========
SmartAssistMetrics
======== */
