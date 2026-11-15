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

    // SPLENDOR_P12_T2_VELOCITY: nominal publish interval (~15fps) used to
    // scale a delta across a real gap back to px/frame; gaps over 2s are
    // too old to trust for velocity at all.
    private const val NOMINAL_FRAME_INTERVAL_MS = 66.7f
    private const val MAX_VELOCITY_GAP_MS = 2000L

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
            // SPLENDOR_P12_T2_STALE_BALL: an undetected ball used to keep stale
            // X/Y forever with zero velocity and no log line, so frozen
            // coordinates read as a stationary ball. Invalidate the position
            // and say so (throttled) instead of silently holding it.
            if (skippedUndetected % 15L == 1L) android.util.Log.w("BallTelemetryBridge", "ball undetected x$skippedUndetected, invalidating stale ball=(${old.ballX},${old.ballY})")
            TelemetryRepository.update(old.copy(confidence = 0f, ballX = 0f, ballY = 0f, ballVelocityX = 0f, ballVelocityY = 0f))
            return
        }

        // Feed the trust chain at the source: without this stamp,
        // ballTrust() decays to zero and the trusted gate locks the whole
        // contributor stack out no matter what the detectors see.
        try { VisionTrust.stampBall(ball.confidence) } catch (e: Throwable) { android.util.Log.w("BallTelemetryBridge", "stampBall failed", e) }

        val now = android.os.SystemClock.elapsedRealtime()
        val dtMs = now - previousAtMs
        // SPLENDOR_P12_T2_VELOCITY: velocity here is px/frame by this
        // codebase's convention (see MagneticFeetContributor). A publish gap
        // used to force vx=vy=0, a false zero indistinguishable from a
        // stopped ball, while the EWA-smoothed position kept decaying by
        // 0.40/frame. Scale the delta by the nominal frame interval over the
        // real gap instead, so a reappearing ball keeps a truthful
        // per-frame velocity; gaps over 2s are too old to trust at all.
        if (dtMs !in 1L..MAX_VELOCITY_GAP_MS) hasPrevious = false
        previousAtMs = now
        val frameScale = if (hasPrevious) (NOMINAL_FRAME_INTERVAL_MS / dtMs.toFloat()).coerceIn(0.05f, 1f) else 0f
        val vx = if (hasPrevious) (ball.x - previousX) * frameScale else 0f
        val vy = if (hasPrevious) (ball.y - previousY) * frameScale else 0f

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
