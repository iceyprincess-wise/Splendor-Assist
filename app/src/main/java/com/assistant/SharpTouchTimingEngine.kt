package com.assistant

import kotlin.math.*
import kotlin.math.hypot

object SharpTouchTimingEngine {

    // No68-I-D14: MIN_SWEET_SPOT_PX (18f) deleted — the old band treated a
    // ball closer than 18 px as worse than the 18-38 px band. A ball already
    // on the player is the best time to act, so only the outer edge (38 px)
    // gates anything now.
    private const val MAX_SWEET_SPOT_PX = 38f
    // SPLENDOR_P10_TOUCH_CONST: FRAME_MS removed with the seconds
    // projection (T3); no remaining use in this file.

    fun evaluateTiming(
        ballX: Float, ballY: Float,
        ballVx: Float, ballVy: Float,
        playerX: Float, playerY: Float,
        playerVx: Float, playerVy: Float
    ): SharpTouchTimingResult {
        // No68-I-D8: the f=0 distance IS the current distance — compute it
        // once and reuse it instead of running a second hypot after the loop.
        val currentDist = hypot((ballX - playerX).toDouble(), (ballY - playerY).toDouble()).toFloat()
        var minDistance = currentDist
        var bestFrame = 0

        // SPLENDOR_P10_TOUCH_UNITS: ball/player velocities are
        // px/frame deltas (BallTelemetryBridge publishes x - previousX,
        // no time division), so the horizon is projected in frames, not
        // seconds. The old code multiplied px/frame by tSec (<= 0.05 s),
        // under-projecting the 3-frame lookahead ~30x.
        for (f in 1..3) {
            val pBallX = ballX + ballVx * f.toFloat()
            val pBallY = ballY + ballVy * f.toFloat()
            val pPlayerX = playerX + playerVx * f.toFloat()
            val pPlayerY = playerY + playerVy * f.toFloat()

            val d = hypot((pBallX - pPlayerX).toDouble(), (pBallY - pPlayerY).toDouble()).toFloat()
            if (d < minDistance) {
                minDistance = d
                bestFrame = f
            }
        }

        // No68-I-D14: a ball closer than MIN_SWEET_SPOT_PX is already on the
        // player — that is the best time to act, not a worse one. The old
        // band scored it 0.9 (below the 18-38 px band's 1.0), and a ball
        // inside 18 px that was still closing was not executeNow at all.
        // At or inside the sweet spot is now executeNow at authority 1.0.
        val inSweetSpotNow = currentDist <= MAX_SWEET_SPOT_PX
        val executeNow = inSweetSpotNow

        val timingAuthority = when {
            inSweetSpotNow -> 1.0f
            bestFrame == 1 -> 0.7f
            else -> 0.3f
        }

        return SharpTouchTimingResult(
            executeNow = executeNow,
            optimalFrameOffset = bestFrame,
            timingAuthority = timingAuthority
        )
    }
}
