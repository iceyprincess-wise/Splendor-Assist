package com.assistant

/**
 * P3 — Off-Match Rate Adapter (OffMatchRateAdapter).
 *
 * Engine-ideas §3 P3. The 60 fps capture target wakes the vision
 * pipeline even when no match is live (in-game menus, home screen,
 * own-UI screens), doing full VisionCore work for zero fresh game
 * frames. This adapter lowers the processed-frame interval to
 * [OFF_MATCH_INTERVAL_MS] (5 fps) whenever the game is NOT in the
 * foreground, and restores the full in-game interval the moment the
 * game returns to the foreground.
 *
 * Match-live signal: the game package is foreground ([inGame], from
 * VisionTrust.isGameForeground()). NOTHING ELSE. Ball absence is
 * deliberately not a signal any more (batch 5b fix): v219 proved the
 * old rule wrong — the ball was almost never detected that session
 * (ballTrust 0.343 at the crash), so ballTrust=0 in-game lowered the
 * rate to 200 ms, which starved the very detection that would restore
 * the ball (ball unseen -> slower -> ball stays unseen; log lines
 * 2026-11-19 16:35:08 + 16:35:19, gameForeground=true ballTrust=0.0).
 * Ball absence in-game is a detection problem, not a no-match signal —
 * throttling on it can only make detection worse. The old
 * MATCH_LOSS_DWELL_MS / NO_BALL_GRACE_MS dwells and the ball-seen
 * tracking (ballSeenEver / lastBallSeenElapsedMs /
 * firstNoBallInGameElapsedMs) became dead with that rule and are
 * removed, not kept: no dead constants left silently.
 *
 * Performance-rule proof: the adapter only changes the processed-frame
 * interval, and only while the game is not foreground — when the game
 * is foreground there is a live match to decide on, so no gameplay
 * decision can change. The in-game full-rate path returns exactly the
 * interval OverlayService computed before (combined memory/brake
 * interval, capped at 33 ms by the V10 floor); D1 holds — CRITICAL
 * memory still cannot slow in-game capture, because the memory/brake
 * interval is an input, never an output, of this adapter. Restoration
 * is automatic: [intervalMs] is evaluated on every frame from live
 * foreground state, so the first frame after the game returns to the
 * foreground runs at the full interval.
 *
 * Pure Kotlin (no Android calls on the exercised path). Test hook
 * [reset] clears the lowered flag; no production caller uses it.
 */
object OffMatchRateAdapter {

    /** Processed-frame interval while no match is live (5 fps). */
    const val OFF_MATCH_INTERVAL_MS = 200L

    /** V10 in-game floor: the interval never exceeds 33 ms at full rate. */
    const val IN_GAME_MAX_INTERVAL_MS = 33L

    @Volatile private var lowered = false

    /** True when the last [intervalMs] call lowered the rate. */
    fun isLowered(): Boolean = lowered

    /** Test hook: clears the lowered flag. No production caller uses it. */
    fun reset() {
        lowered = false
    }

    /**
     * Returns the capture frame interval for this frame.
     *
     * @param inGame game package foreground (VisionTrust.isGameForeground())
     * @param combinedMs the memory/brake combined interval OverlayService already computed
     */
    fun intervalMs(inGame: Boolean, combinedMs: Long): Long {
        // Batch 5b: the ONLY lowering leg is game-not-foreground.
        // In-game the full rate holds whatever the ball state is.
        val loweredNow = !inGame
        lowered = loweredNow
        return if (loweredNow) maxOf(combinedMs, OFF_MATCH_INTERVAL_MS)
        else combinedMs.coerceAtMost(IN_GAME_MAX_INTERVAL_MS)
    }
}
