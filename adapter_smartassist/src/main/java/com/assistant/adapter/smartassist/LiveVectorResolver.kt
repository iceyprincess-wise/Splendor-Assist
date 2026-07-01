package com.assistant.adapter.smartassist

import kotlin.math.hypot

/**
 * Resolves Smart Assist action coordinates.
 * Uses live telemetry when the scan has real motion; otherwise falls back to
 * active screen-relative vectors so the engines NEVER go silent.
 */
object LiveVectorResolver {

    data class LiveVector(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val duration: Long,
        val hasRealData: Boolean
    )

    fun resolve(frameWidth: Float, frameHeight: Float): LiveVector {
        val t = TelemetryRepository.current()

        val hasReal = t.ballX != 0f || t.ballY != 0f ||
                      t.playerVelocity != 0f ||
                      t.opponentDistance != Float.MAX_VALUE

        // Anchor: real goalkeeper/player position, else lower-left attacking zone.
        val startX = if (t.goalkeeperX != 0f) t.goalkeeperX else frameWidth * 0.30f
        val startY = if (t.goalkeeperY != 0f) t.goalkeeperY else frameHeight * 0.62f

        // Target: predicted ball if real, else forward attacking vector toward goal.
        val lookAhead = 6f
        val endX = if (hasReal) (t.ballX + t.ballVelocityX * lookAhead).coerceIn(0f, frameWidth)
                   else frameWidth * 0.70f
        val endY = if (hasReal) (t.ballY + t.ballVelocityY * lookAhead).coerceIn(0f, frameHeight)
                   else frameHeight * 0.40f

        val duration = (90L - (t.playerVelocity * 40f).toLong()).coerceIn(35L, 120L)

        // ALWAYS act — engines stay alive. Real data just sharpens the target.
        return LiveVector(startX, startY, endX, endY, duration, true)
    }
}
