package com.assistant

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

        // SPLENDOR_P12_T2_STALE: a frozen snapshot used to read as real data
        // (any nonzero ball coordinate counted), so a stale point produced a
        // "real" vector. Require a fresh snapshot — freshness reads the
        // SPLIT ball timestamp (B2 / Q68-54: the shared stamp was
        // refreshed by keeper writes, so a stale ball read as fresh).
        val ballStamped = if (t.ballTimestampMs > 0L) t.ballTimestampMs else t.timestamp
        val snapshotFresh = System.currentTimeMillis() - ballStamped <= MAX_TELEMETRY_AGE_MS
        val hasReal = snapshotFresh && (t.ballX != 0f || t.ballY != 0f ||
                      t.playerVelocity != 0f ||
                      t.opponentDistance != Float.MAX_VALUE)

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

        // Preserve fallback vectors, but expose whether telemetry is actually live.
        return LiveVector(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration,
            hasRealData = hasReal
        )
    }
}
