package com.assistant

/**
 * B2 — Ball Telemetry Resync (engine-ideas §2, N3-trace B; Q68-54
 * split freshness lands with it).
 *
 * Bottleneck: the keeper scan's ball was frozen while live telemetry
 * moved — the scan read a stale ball, so `ballSeen` failed on 250 ms
 * freshness / `trackedBallVisible` and 877 of 947 decisions exited
 * `idleUntrusted`. Two defects fed it: (1) one shared timestamp served
 * both the ball and the keeper, so a keeper write refreshed ball
 * freshness (and vice versa); (2) nothing re-read the fresh ball when
 * the telemetry slot went stale.
 *
 * TRACE: CALLER OmnipotentGoalkeeperEngine.scanFrameForOpponentAnimation
 * (scan entry, before it reads the ball) + FrameAssembler (ballSeen) →
 * ENGINE this resync (decide + republish) → CALLEE
 * TelemetryCoordinator.updateBallMotion (the single ball writer) →
 * DATA FLOW fresh SceneTracker ball → telemetry slot → DECISION
 * resync when telemetry is stale AND the scene ball is fresh and
 * visible; else BALL_STALE → MUTATION telemetry ball position refreshed
 * (velocity kept from the last measurement — a resync must not invent
 * a velocity) → CONSUMER keeper scan + FrameAssembler trust → LIVE
 * EFFECT the keeper scan reads a live ball instead of the frozen one.
 *
 * Q68-54: TelemetrySnapshot now carries ballTimestampMs +
 * goalkeeperTimestampMs beside the legacy shared `timestamp`. The ball
 * writer stamps only the ball field, the keeper writer only the keeper
 * field; consumers read the split fields (legacy `timestamp` is kept
 * for old readers and still stamped by both writers). A player-motion
 * write never touches either split field.
 */
object BallTelemetryResync {

    /** Same freshness window the frame trust gate already uses. */
    const val MAX_BALL_AGE_MS = 250L

    enum class ResyncDecision { FRESH, RESYNCED, STALE }

    data class ResyncOutcome(
        val decision: ResyncDecision,
        val ballX: Float,
        val ballY: Float,
        val ballAgeMs: Long
    )

    @Volatile private var lastStaleLogMs = 0L
    @Volatile private var lastResyncLogMs = 0L

    /**
     * Pure decision core (harness-pinned): given the telemetry ball's
     * age and whether the scene ball is fresh + visible, say whether
     * the telemetry is fresh, resyncable, or stale.
     */
    fun decide(ballAgeMs: Long, sceneBallFresh: Boolean, sceneBallVisible: Boolean): ResyncDecision =
        when {
            ballAgeMs in 0L..MAX_BALL_AGE_MS -> ResyncDecision.FRESH
            sceneBallFresh && sceneBallVisible -> ResyncDecision.RESYNCED
            else -> ResyncDecision.STALE
        }

    /** Ball age from the SPLIT timestamp (falls back to the legacy shared stamp). */
    fun ballAgeMs(snapshot: TelemetrySnapshot, nowMs: Long = System.currentTimeMillis()): Long {
        val stamped = if (snapshot.ballTimestampMs > 0L) snapshot.ballTimestampMs else snapshot.timestamp
        return nowMs - stamped
    }

    /**
     * Resyncs the keeper scan's ball from fresh telemetry (the scene
     * ball) when the telemetry slot is stale. Safe to call on every
     * scan: a fresh slot is a no-op, a stale slot with no fresh scene
     * ball logs BALL_STALE (throttled) and changes nothing.
     */
    fun resyncIfStale(nowMs: Long = System.currentTimeMillis()): ResyncOutcome {
        val telemetry = try { TelemetryRepository.current() } catch (_: Throwable) { null }
            ?: return ResyncOutcome(ResyncDecision.STALE, 0f, 0f, Long.MAX_VALUE)
        val ageMs = ballAgeMs(telemetry, nowMs)
        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val sceneFresh = scene != null && nowMs - scene.timestamp in 0L..MAX_BALL_AGE_MS
        val sceneVisible = scene?.trackedBallVisible == true
        return when (decide(ageMs, sceneFresh, sceneVisible)) {
            ResyncDecision.FRESH ->
                ResyncOutcome(ResyncDecision.FRESH, telemetry.ballX, telemetry.ballY, ageMs)
            ResyncDecision.STALE -> {
                if (nowMs - lastStaleLogMs >= 60_000L) {
                    lastStaleLogMs = nowMs
                    try {
                        com.assistant.diagnostic.RuntimeLogger.log(
                            "BALL_STALE ballAgeMs=$ageMs sceneFresh=$sceneFresh sceneVisible=$sceneVisible (keeper scan keeps the stale ball; no fresh scene ball to resync from)",
                            "GOALKEEPER"
                        )
                    } catch (_: Throwable) { }
                }
                ResyncOutcome(ResyncDecision.STALE, telemetry.ballX, telemetry.ballY, ageMs)
            }
            ResyncDecision.RESYNCED -> {
                val s = scene!!
                try {
                    // Position only: the velocity stays the last measured
                    // one — a resync must not invent a velocity, and the
                    // scene carries a scalar speed, not a vector.
                    TelemetryCoordinator.updateBallMotion(
                        s.trackedBallX,
                        s.trackedBallY,
                        telemetry.ballVelocityX,
                        telemetry.ballVelocityY,
                        s.confidence
                    )
                } catch (_: Throwable) { }
                if (nowMs - lastResyncLogMs >= 60_000L) {
                    lastResyncLogMs = nowMs
                    try {
                        com.assistant.diagnostic.RuntimeLogger.log(
                            "BALL_RESYNC ballAgeMs=$ageMs -> scene ball=(${s.trackedBallX},${s.trackedBallY}) (telemetry slot was stale; keeper scan resynced from the fresh scene ball)",
                            "GOALKEEPER"
                        )
                    } catch (_: Throwable) { }
                }
                ResyncOutcome(ResyncDecision.RESYNCED, s.trackedBallX, s.trackedBallY, ageMs)
            }
        }
    }
}
