package com.assistant.overlay.interceptor

import kotlin.math.hypot

/**
 * Item 12 (12a) — Pre-shot 1v1 rush decision (pure, no Android).
 *
 * ROOT CAUSE (PROVEN): the keeper's only proximity trigger lived behind
 * the shot-gauge gate — `OmnipotentGoalkeeperEngine.scanFrame` only calls
 * `processGoalkeeperDefensiveLayer` when a gauge-coloured pixel is found
 * in the bottom 25% of the frame. A dribbling attacker 1v1 with the
 * keeper, with no shot gauge visible, produced no decision at all: the
 * keeper stood and waited until the player kicked. The wait was
 * unbounded, not a cooldown.
 *
 * This engine decides the rush from REAL tracked positions (keeper +
 * nearest opponent to the ball + ball), with no gauge input at all:
 * rush when the attacker is close to the keeper AND in possession of
 * the ball AND the keeper is not already closing on him fast.
 *
 * Numbers (all inherited, not re-tuned):
 * - RUSH_DISTANCE_PX = 200f — the defensive layer's own 1v1 rule.
 * - POSSESSION_DISTANCE_PX = 60f — the repo's possession rule
 *   (`BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE`).
 * - ALREADY_CLOSING_PX_PER_FRAME = 2f — the same floor as the ball
 *   deflection rule (40 px/s at the 20 fps processed rate), expressed
 *   in the predictor's per-frame unit so no fps is guessed.
 * The keeper velocity is px per FRAME here (the predictor's unit).
 */
object PreShotRushEngine {

    const val RUSH_DISTANCE_PX = 200f
    const val POSSESSION_DISTANCE_PX = 60f
    const val ALREADY_CLOSING_PX_PER_FRAME = 2f

    /**
     * @param keeperVelXPerFrame keeper velocity, px per frame.
     * @param keeperVelYPerFrame keeper velocity, px per frame.
     * @return true when the keeper should rush the attacker now.
     */
    fun shouldRush(
        keeperX: Float,
        keeperY: Float,
        keeperVelXPerFrame: Float,
        keeperVelYPerFrame: Float,
        attackerX: Float,
        attackerY: Float,
        ballX: Float,
        ballY: Float
    ): Boolean {
        if (!keeperX.isFinite() || !keeperY.isFinite() ||
            !attackerX.isFinite() || !attackerY.isFinite() ||
            !ballX.isFinite() || !ballY.isFinite()
        ) return false
        // Attacker must be close to the keeper (the 1v1).
        val attackerToKeeper = hypot(attackerX - keeperX, attackerY - keeperY)
        if (attackerToKeeper >= RUSH_DISTANCE_PX) return false
        // The attacker must actually have the ball (possession rule) —
        // a nearby attacker without the ball is not a 1v1 breakaway.
        val ballToAttacker = hypot(ballX - attackerX, ballY - attackerY)
        if (ballToAttacker > POSSESSION_DISTANCE_PX) return false
        // Do not rush when the keeper is already closing fast on the
        // attacker — he is already doing the job; a second rush swipe
        // is redundant. Closing speed is the velocity component along
        // the keeper->attacker direction, in px per frame.
        val dx = attackerX - keeperX
        val dy = attackerY - keeperY
        val dist = hypot(dx, dy)
        if (dist > 0f) {
            val closingSpeed =
                (keeperVelXPerFrame * dx + keeperVelYPerFrame * dy) / dist
            if (closingSpeed >= ALREADY_CLOSING_PX_PER_FRAME) return false
        }
        return true
    }
}
