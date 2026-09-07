package com.assistant.adapter.smartassist

import kotlin.math.hypot

data class SharpTouchTimingResult(
    val executeNow: Boolean,
    val optimalFrameOffset: Int,
    val ballDistancePx: Float,
    val timingAuthority: Float
)

object SharpTouchTimingEngine {

    private const val MIN_SWEET_SPOT_PX = 18f
    private const val MAX_SWEET_SPOT_PX = 38f
    private const val FRAME_MS = 16.666666f

    fun evaluateTiming(
        ballX: Float, ballY: Float,
        ballVx: Float, ballVy: Float,
        playerX: Float, playerY: Float,
        playerVx: Float, playerVy: Float
    ): SharpTouchTimingResult {
        var minDistance = Float.MAX_VALUE
        var bestFrame = 0

        for (f in 0..3) {
            val tSec = (f * FRAME_MS) / 1000f
            val pBallX = ballX + ballVx * tSec
            val pBallY = ballY + ballVy * tSec
            val pPlayerX = playerX + playerVx * tSec
            val pPlayerY = playerY + playerVy * tSec

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
