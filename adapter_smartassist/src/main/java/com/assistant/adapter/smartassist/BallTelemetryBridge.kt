package com.assistant.adapter.smartassist

/*
 * The missing wire between detection and trust.
 *
 * BallDetector produced a BallDetectionResult every frame, but nothing ever
 * pushed it into TelemetryRepository -- so FrameAssembler always saw
 * ballX/ballY == 0, hasBall stayed false, the frame was never trusted, and
 * only DefenseContributor could act.
 *
 * This bridge is the single writer of ball telemetry. It publishes ONLY on a
 * real detection, and derives velocity from its own previous position so it
 * needs no other engine's internals.
 */
object BallTelemetryBridge {

    @Volatile private var previousX = 0f
    @Volatile private var previousY = 0f
    @Volatile private var hasPrevious = false

    @Volatile private var published = 0L
    @Volatile private var skippedUndetected = 0L
    @Volatile private var lastConfidence = 0f
    @Volatile private var lastUpdatedMs = 0L

    fun publish(ball: BallDetectionResult) {
        if (!ball.detected) {
            skippedUndetected++
            hasPrevious = false
            return
        }

        val vx = if (hasPrevious) ball.x - previousX else 0f
        val vy = if (hasPrevious) ball.y - previousY else 0f

        try {
            TelemetryCoordinator.updateBallMotion(
                ball.x.coerceAtLeast(0f),
                ball.y.coerceAtLeast(0f),
                vx,
                vy
            )
            published++
            lastConfidence = ball.confidence
            lastUpdatedMs = System.currentTimeMillis()
        } catch (_: Throwable) {
        }

        previousX = ball.x
        previousY = ball.y
        hasPrevious = true
    }

    fun reset() {
        previousX = 0f
        previousY = 0f
        hasPrevious = false
        published = 0L
        skippedUndetected = 0L
        lastConfidence = 0f
        lastUpdatedMs = 0L
    }

    fun ballTelemetryRuntimeSnapshot(): Map<String, Any> = mapOf(
        "published" to published,
        "skippedUndetected" to skippedUndetected,
        "lastConfidence" to lastConfidence,
        "lastUpdatedMs" to lastUpdatedMs
    )
}
