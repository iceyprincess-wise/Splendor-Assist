package com.assistant

/**
 * P3 — Off-Match Rate Adapter (OffMatchRateAdapter).
 *
 * Engine-ideas §3 P3. The 60 fps capture target wakes the vision
 * pipeline even when no match is live (in-game menus, home screen,
 * own-UI screens), doing full VisionCore work for zero fresh game
 * frames. This adapter lowers the processed-frame interval to
 * [OFF_MATCH_INTERVAL_MS] (5 fps) whenever no match is live, and
 * restores the full in-game interval the moment a match is live again.
 *
 * Match-live signal: the game package is foreground
 * ([inGame], from VisionTrust.isGameForeground()) AND the ball has
 * been seen recently (ballTrust > 0, stamped by BallTelemetryBridge
 * on live telemetry). A ball sighting restores full rate immediately;
 * a ball loss only lowers the rate after [MATCH_LOSS_DWELL_MS], so a
 * brief occlusion, replay, or cutscene cannot flap the rate. Before
 * the ball has ever been seen, the adapter holds full rate for
 * [NO_BALL_GRACE_MS] (kickoff: the ball appears within ~1 s of a
 * match starting) and only then lowers — so the in-game menu case
 * (game foreground, ball never stamped) is still shed, while a match
 * whose ball detection is merely slow to start is not throttled at
 * kickoff.
 *
 * Performance-rule proof: the adapter only changes the processed-frame
 * interval, and only while no match is live — when the game is not
 * foreground, or the ball has been absent for longer than the dwell,
 * there is no live ball for any decision to read, so no gameplay
 * decision can change. The in-game full-rate path returns exactly the
 * interval OverlayService computed before (combined memory/brake
 * interval, capped at 33 ms by the V10 floor); D1 holds — CRITICAL
 * memory still cannot slow in-game capture, because the memory/brake
 * interval is an input, never an output, of this adapter. Restoration
 * is automatic: [intervalMs] is evaluated on every frame from live
 * foreground/ball state, so the first ball sighting after a menu
 * returns the 60-target interval within one frame.
 *
 * Pure Kotlin (no Android calls on the exercised path): the caller
 * passes elapsed-realtime milliseconds. Test hook [reset] clears the
 * ball-seen state; no production caller uses it.
 */
object OffMatchRateAdapter {

    /** Processed-frame interval while no match is live (5 fps). */
    const val OFF_MATCH_INTERVAL_MS = 200L

    /** Ball-absent dwell before the rate is lowered in-game. */
    const val MATCH_LOSS_DWELL_MS = 3000L

    /** Full-rate grace while the ball has never been seen in-game. */
    const val NO_BALL_GRACE_MS = 10_000L

    /** V10 in-game floor: the interval never exceeds 33 ms at full rate. */
    const val IN_GAME_MAX_INTERVAL_MS = 33L

    @Volatile private var ballSeenEver = false
    @Volatile private var lastBallSeenElapsedMs = 0L
    @Volatile private var firstNoBallInGameElapsedMs = 0L
    @Volatile private var lowered = false

    /** True when the last [intervalMs] call lowered the rate. */
    fun isLowered(): Boolean = lowered

    /** Test hook: clears the ball-seen state. No production caller uses it. */
    fun reset() {
        ballSeenEver = false
        lastBallSeenElapsedMs = 0L
        firstNoBallInGameElapsedMs = 0L
        lowered = false
    }

    /**
     * Returns the capture frame interval for this frame.
     *
     * @param inGame game package foreground (VisionTrust.isGameForeground())
     * @param ballTrust age-decayed ball trust (VisionTrust.ballTrust()); > 0 means the ball was seen recently
     * @param nowElapsedMs android.os.SystemClock.elapsedRealtime()
     * @param combinedMs the memory/brake combined interval OverlayService already computed
     */
    fun intervalMs(inGame: Boolean, ballTrust: Float, nowElapsedMs: Long, combinedMs: Long): Long {
        if (ballTrust > 0f) {
            ballSeenEver = true
            lastBallSeenElapsedMs = nowElapsedMs
            firstNoBallInGameElapsedMs = 0L
        }
        val loweredNow = when {
            !inGame -> true
            ballTrust > 0f -> false
            !ballSeenEver -> {
                // In-game but the ball has never been seen: full rate for
                // the grace window (kickoff), lowered after it (menu).
                if (firstNoBallInGameElapsedMs == 0L) firstNoBallInGameElapsedMs = nowElapsedMs
                nowElapsedMs - firstNoBallInGameElapsedMs >= NO_BALL_GRACE_MS
            }
            else -> nowElapsedMs - lastBallSeenElapsedMs >= MATCH_LOSS_DWELL_MS
        }
        lowered = loweredNow
        return if (loweredNow) maxOf(combinedMs, OFF_MATCH_INTERVAL_MS)
        else combinedMs.coerceAtMost(IN_GAME_MAX_INTERVAL_MS)
    }
}
