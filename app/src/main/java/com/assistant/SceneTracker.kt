package com.assistant

import kotlin.math.*
import kotlin.math.atan2

object SceneTracker {

    private var frameCounter = 0L

    private var trackedBallX = 0f
    private var trackedBallY = 0f
    private var trackedBallSpeed = 0f

    @Volatile private var latest =
        SceneSnapshot()

    private val trackedPlayers = mutableListOf<TrackedPlayer>()

    private val mutationLock = Any()

    // B6: measured inter-update gap for the px/s conversion
    // (PlayerMotionProducer). 0 = no previous update yet.
    private var lastUpdateElapsedMs = 0L

    private var trackedGoalkeeper: TrackedPlayer? = null

    // ADMIN-TUNABLE (defaults = original hard-coded values)
    private val PLAYER_MEMORY_FRAMES: Long
        get() = 15L
    private val MAX_TRACKS: Int
        get() = 30

    private const val CONFIDENCE_DECAY = 0.05f

    /**
     * Test hook (no53 round): resets the frame counter, the tracked ball
     * state, the published snapshot and all tracks, under the same lock
     * update() uses. No production caller uses it. Tests call it in
     * @Before so JUnit's hash method order can never flip a result.
     */
    fun reset() = synchronized(mutationLock) {
        frameCounter = 0L
        trackedBallX = 0f
        trackedBallY = 0f
        trackedBallSpeed = 0f
        lastUpdateElapsedMs = 0L
        trackedPlayers.clear()
        trackedGoalkeeper = null
        latest = SceneSnapshot()
    }

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

                // Item 12 (C4): EMA-smooth the keeper velocity — the raw
                // one-frame delta amplified 6x by the look-ahead turns
                // one noisy detection into a 6x prediction jump. The
                // 0.5/0.5 mix is stated tuning.
                keeper.velocityX =
                    keeper.velocityX * 0.5f + (state.goalkeeperX - keeper.x) * 0.5f

                keeper.velocityY =
                    keeper.velocityY * 0.5f + (state.goalkeeperY - keeper.y) * 0.5f

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

        // B6 (PlayerMotionProducer): per-player velocity in px/s from the
        // MEASURED inter-update gap — never from a guessed fps. The
        // per-frame EMA displacement (velocityX/Y) times 1000/dtMs is
        // px/s; an unbelievable gap (first update, stall, clock jump)
        // yields 0, never a scaled-up guess. Runs after associate() AND
        // after the keeper displacement above, so the displacement it
        // converts is this frame's for every track. Wall clock, not
        // elapsedRealtime: the plain-JVM harness cannot run the Android
        // clock stub, and a wall-clock jump only ever lands outside the
        // 8..250 ms window — i.e. 0, the safe direction — never a wrong
        // velocity.
        run {
            val nowMs = System.currentTimeMillis()
            val dtMs = if (lastUpdateElapsedMs == 0L) 0L else nowMs - lastUpdateElapsedMs
            lastUpdateElapsedMs = nowMs
            val convert = { track: TrackedPlayer ->
                track.velocityXPerSecond = PlayerMotionProducer.perSecond(track.velocityX, dtMs)
                track.velocityYPerSecond = PlayerMotionProducer.perSecond(track.velocityY, dtMs)
            }
            trackedPlayers.forEach(convert)
            trackedGoalkeeper?.let(convert)
        }

        // prune the dead: unseen too long or fully faded
        val memory = PLAYER_MEMORY_FRAMES
        if (trackedGoalkeeper?.let { frameCounter - it.lastSeenFrame > memory } == true) {
            trackedGoalkeeper = null
        }
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

        // Item 12 (C14): a coasted keeper's kinematics must not leak
        // into the snapshot as if he were seen — readers that use X/Y
        // without checking `visible` would steer on a stale keeper (up
        // to 15 frames of coast). Zero them when invisible.
        val gkVisible =
            state.goalkeeperDetected && state.goalkeeperConfidence > 0f

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

                goalkeeperVisible = gkVisible,

                goalkeeperX =
                    if (gkVisible) trackedGoalkeeper?.x ?: 0f else 0f,

                goalkeeperY =
                    if (gkVisible) trackedGoalkeeper?.y ?: 0f else 0f,

                goalkeeperVelocityX =
                    if (gkVisible) trackedGoalkeeper?.velocityX ?: 0f else 0f,

                goalkeeperVelocityY =
                    if (gkVisible) trackedGoalkeeper?.velocityY ?: 0f else 0f,

                goalkeeperHeading =
                    if (gkVisible) trackedGoalkeeper?.headingRadians ?: 0f else 0f,

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

                trackedPlayers = trackedPlayers.map { it.copy() },

                confidence = state.confidence,
                goalkeeperConfidence = trackedGoalkeeper?.confidence ?: 0f
            )

        // Batch-trajectory (B): feed the keeper predictor from the
        // measured keeper track — same 6-frame look-ahead maths as the
        // ball predictor (~300 ms at the 20 fps processed rate). Valid
        // only when the keeper was seen on THIS frame
        // (lastSeenFrame == frameCounter); a coasted keeper's velocity
        // is stale, so the prediction is marked invalid and consumers
        // fall back to the current position.
        val keeper = trackedGoalkeeper
        if (keeper != null && keeper.lastSeenFrame == frameCounter) {
            KeeperTrajectoryPredictor.update(
                keeper.velocityX,
                keeper.velocityY,
                keeper.x + keeper.velocityX * KeeperTrajectoryPredictor.LOOK_AHEAD_FRAMES,
                keeper.y + keeper.velocityY * KeeperTrajectoryPredictor.LOOK_AHEAD_FRAMES,
                true
            )
        } else {
            KeeperTrajectoryPredictor.update(0f, 0f, 0f, 0f, false)
        }

        latest
    }

    fun current(): SceneSnapshot =
        latest
}
