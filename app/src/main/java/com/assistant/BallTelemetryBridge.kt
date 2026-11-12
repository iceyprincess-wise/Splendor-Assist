package com.assistant























import android.os.SystemClock
import android.util.Log













import com.assistant.runtime.*


























import kotlin.math.*




























object BallTelemetryBridge {

    @Volatile private var previousX = 0f
    @Volatile private var previousY = 0f
    @Volatile private var hasPrevious = false
    private var previousAtMs = 0L

    @Volatile private var published = 0L
    @Volatile private var skippedUndetected = 0L
    @Volatile private var lastConfidence = 0f
    @Volatile private var lastUpdatedMs = 0L

    fun publish(ball: BallDetectionResult) {
        if (!ball.detected || !ball.observed || !ball.x.isFinite() || !ball.y.isFinite() ||
            !ball.confidence.isFinite() || ball.confidence <= 0f) {
            skippedUndetected++
            hasPrevious = false
            val old = TelemetryRepository.current()
            TelemetryRepository.update(old.copy(confidence = 0f, ballVelocityX = 0f, ballVelocityY = 0f))
            return
        }

        // Feed the trust chain at the source: without this stamp,
        // ballTrust() decays to zero and the trusted gate locks the whole
        // contributor stack out no matter what the detectors see.
        try { VisionTrust.stampBall(ball.confidence) } catch (e: Throwable) { android.util.Log.w("BallTelemetryBridge", "stampBall failed", e) }

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - previousAtMs !in 1L..MAX_TELEMETRY_AGE_MS) hasPrevious = false
        previousAtMs = now
        val vx = if (hasPrevious) ball.x - previousX else 0f
        val vy = if (hasPrevious) ball.y - previousY else 0f

        try {
            TelemetryCoordinator.updateBallMotion(
                ball.x.coerceAtLeast(0f),
                ball.y.coerceAtLeast(0f),
                vx,
                vy,
                ball.confidence
            )
            published++
            lastConfidence = ball.confidence
            lastUpdatedMs = System.currentTimeMillis()
        } catch (e: Throwable) { android.util.Log.w("BallTelemetryBridge", "updateBallMotion failed", e) }

        try { VisionTrust.pushMotion(vx, vy) } catch (e: Throwable) { android.util.Log.w("BallTelemetryBridge", "pushMotion failed", e) }

        previousX = ball.x
        previousY = ball.y
        hasPrevious = true
    }

    fun reset() {
        previousX = 0f
        previousY = 0f
        hasPrevious = false
        previousAtMs = 0L
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

/* ========
BallTrajectoryPredictor
======== */
