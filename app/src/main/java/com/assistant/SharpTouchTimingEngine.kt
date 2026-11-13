package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object SharpTouchTimingEngine {

    private const val MIN_SWEET_SPOT_PX = 18f
    private const val MAX_SWEET_SPOT_PX = 38f
    // SPLENDOR_P10_TOUCH_CONST: FRAME_MS removed with the seconds
    // projection (T3); no remaining use in this file.

    fun evaluateTiming(
        ballX: Float, ballY: Float,
        ballVx: Float, ballVy: Float,
        playerX: Float, playerY: Float,
        playerVx: Float, playerVy: Float
    ): SharpTouchTimingResult {
        var minDistance = Float.MAX_VALUE
        var bestFrame = 0

        // SPLENDOR_P10_TOUCH_UNITS: ball/player velocities are
        // px/frame deltas (BallTelemetryBridge publishes x - previousX,
        // no time division), so the horizon is projected in frames, not
        // seconds. The old code multiplied px/frame by tSec (<= 0.05 s),
        // under-projecting the 3-frame lookahead ~30x.
        for (f in 0..3) {
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

        val currentDist = hypot((ballX - playerX).toDouble(), (ballY - playerY).toDouble()).toFloat()
        val inSweetSpotNow = currentDist in MIN_SWEET_SPOT_PX..MAX_SWEET_SPOT_PX
        val executeNow = inSweetSpotNow || (bestFrame == 0 && currentDist <= MAX_SWEET_SPOT_PX)

        val timingAuthority = when {
            inSweetSpotNow -> 1.0f
            executeNow -> 0.9f
            bestFrame == 1 -> 0.7f
            else -> 0.3f
        }

        return SharpTouchTimingResult(
            executeNow = executeNow,
            optimalFrameOffset = bestFrame,
            ballDistancePx = currentDist,
            timingAuthority = timingAuthority
        )
    }
}

/* ========
PassingLaneGraph
======== */
