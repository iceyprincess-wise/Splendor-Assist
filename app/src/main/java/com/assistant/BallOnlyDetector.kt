package com.assistant

/**
 * L1 — Ball-Only Vision Path (BallOnlyDetector).
 *
 * Gap (Q-N7-2 / Q68-15): on CRITICAL memory the app could not shed vision
 * load — it either kept full vision under pressure or dropped vision
 * entirely; `MemoryCaptureGateEngine.shouldSkipFullVision()` had zero
 * callers and no ball-only path existed to skip *to*.
 *
 * Contract: in = latest ball detection (+ goal / nearest players when the
 * mode asks for them); out = the mode that produced it (`BallOnlyMode`);
 * caller = VisionCore.process() on CRITICAL (the first live caller of
 * shouldSkipFullVision()); consumer = BallTelemetryBridge (keeper scan +
 * FrameAssembler trust path read the published ball). Item 15 (Letter B):
 * the old result struct + select() are deleted — the struct only
 * echoed values already live through other paths, and the CRITICAL-memory
 * diagnostic log now reads the locals directly in VisionCore.
 *
 * D3 (Ed, §5): the mode is ZONE-CONDITIONAL on ball position — the ball's
 * third along the attack axis (X: the goal sits at an X end) decides what
 * the path still detects:
 *   own third      -> BALL_GOAL    (ball + goal: the keeper needs the posts)
 *   opponent third -> BALL_PLAYERS (ball + nearest players: the attack needs options)
 *   middle third   -> BALL_ONLY    (ball alone)
 * Attack direction comes from AttackAxisModel: attack right puts our goal
 * at the left edge; attack left puts it at the right edge; unconfident
 * direction (0) keeps the historical left-edge default.
 *
 * D1 (Ed, §5): CRITICAL memory must NOT slow in-game capture — this path
 * is strictly cheaper than full vision (it skips the field/keeper/
 * formation/lane/learning engines), and the capture interval stays
 * V10-capped by MemoryCaptureGateEngine.recommendedIntervalMs().
 *
 * Pure Kotlin (no Android): the mode/selection math is pinned in the
 * batch-1 harness (group 20).
 */
enum class BallOnlyMode {
    BALL_ONLY,
    BALL_GOAL,
    BALL_PLAYERS
}

object BallOnlyDetector {

    /**
     * Zone-conditional mode from the ball's X third. Width <= 0 or a
     * non-finite ball X degrades to BALL_ONLY (never guess a third).
     */
    fun modeFor(ballX: Float, captureWidth: Float, attackDirection: Int): BallOnlyMode {
        if (captureWidth <= 0f || !ballX.isFinite()) return BallOnlyMode.BALL_ONLY
        val ownThird = if (attackDirection < 0) ballX > captureWidth * 2f / 3f
            else ballX < captureWidth / 3f
        if (ownThird) return BallOnlyMode.BALL_GOAL
        val opponentThird = if (attackDirection < 0) ballX < captureWidth / 3f
            else ballX > captureWidth * 2f / 3f
        if (opponentThird) return BallOnlyMode.BALL_PLAYERS
        return BallOnlyMode.BALL_ONLY
    }
}
