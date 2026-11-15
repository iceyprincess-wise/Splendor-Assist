package com.assistant




























import com.assistant.diagnostic.RuntimeLogger









import com.assistant.runtime.*


























import kotlin.math.*




























object TelemetryCoordinator {

    fun updatePlayerMotion(
        velocity: Float,
        opponentDistance: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                playerVelocity = velocity,
                opponentDistance = opponentDistance
            )
        )

        RuntimeLogger.telemetry("playerVelocity=$velocity opponentDistance=$opponentDistance")
    }

    fun updateBallMotion(
        x: Float,
        y: Float,
        velocityX: Float,
        velocityY: Float,
        confidence: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                ballX = x,
                ballY = y,
                ballVelocityX = velocityX,
                ballVelocityY = velocityY,
                confidence = confidence.coerceIn(0f, 1f),
                timestamp = System.currentTimeMillis(),
                captureWidth = com.assistant.vision.CameraProfile.captureWidth,
                captureHeight = com.assistant.vision.CameraProfile.captureHeight
            )
        )

        RuntimeLogger.telemetry("ball=($x,$y) velocity=($velocityX,$velocityY)")
    }

    fun updateGoalkeeperPosition(
        x: Float,
        y: Float,
        confidence: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                goalkeeperX = x,
                goalkeeperY = y,
                goalkeeperConfidence =
                    if (confidence.isFinite()) confidence.coerceIn(0f, 1f) else 0f,
                // SPLENDOR_P12_T2_TIMESTAMP: only the ball updater stamped
                // the snapshot, so GK-only frames read as stale. Any real
                // telemetry write refreshes freshness.
                timestamp = System.currentTimeMillis(),
                captureWidth = com.assistant.vision.CameraProfile.captureWidth,
                captureHeight = com.assistant.vision.CameraProfile.captureHeight
            )
        )

        RuntimeLogger.telemetry("goalkeeper=($x,$y)")
    }
}

/* ========
TelemetryRepository
======== */
