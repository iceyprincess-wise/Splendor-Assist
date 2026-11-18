package com.assistant























import android.os.SystemClock
import android.util.Log













import com.assistant.runtime.*


























import kotlin.math.*




























object BallTelemetryBridge {

    @Volatile private var previousX = 0f
    @Volatile private var previousY = 0f
    @Volatile private var hasPrevious = false
    // No68-E-D1: previousAtMs was the only velocity field NOT @Volatile
    // while publish() (VisionCore thread) and reset() (RuntimeCoordinator
    // thread) race on it; a stale read makes dtMs wrong and the velocity
    // wrong. Volatile like the other velocity fields.
    // No68-E-D34: was a plain var while its siblings are @Volatile.
    // publish() runs on the single vision thread today, so this is a
    // consistency fix, not a live race.
    @Volatile private var previousAtMs = 0L

    // SPLENDOR_P12_T2_VELOCITY: nominal publish interval (~15fps) used to
    // scale a delta across a real gap back to px/frame; gaps over 2s are
    // too old to trust for velocity at all.
    private const val NOMINAL_FRAME_INTERVAL_MS = 66.7f
    private const val MAX_VELOCITY_GAP_MS = 2000L
    // No68-E-D3: a gap under one 120Hz frame (8ms) is timestamp noise, not
    // motion — the old code capped the scale at 1.0, so a 1ms gap still
    // reported the whole delta as one frame of velocity. Treat it as no
    // previous frame instead.
    private const val MIN_VELOCITY_GAP_MS = 8L

    @Volatile private var published = 0L
    @Volatile private var skippedUndetected = 0L
    @Volatile private var lastConfidence = 0f
    @Volatile private var lastUpdatedMs = 0L

    // No68-E-D1/D2: publish() and reset() run on different threads
    // (VisionCore vs RuntimeCoordinator); synchronise both so a reset
    // cannot land between the velocity read and the previous-position
    // write and leave hasPrevious=true with zeroed coordinates.
    @Synchronized
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
            // No68-E-D4: the invalidation kept the OLD timestamp, so the
            // (0,0) snapshot read as a real ball seen at the last detection
            // time. Stamp the invalidation time instead.
            // No68-E-D33: through the atomic transform (same effect) so a
            // concurrent goalkeeper write cannot be clobbered by this
            // whole-snapshot replacement.
            TelemetryRepository.update { it.copy(confidence = 0f, ballX = 0f, ballY = 0f, ballVelocityX = 0f, ballVelocityY = 0f, timestamp = System.currentTimeMillis()) }
            return
        }

        // No68-E-D2: clamp once, at the door. The old code passed the raw
        // x/y to VisionTrust/previousX/Y but the >=0-coerced x/y to the
        // coordinator, so velocity was computed from uncoerced coordinates
        // and lastConfidence could store a confidence > 1.
        val cx = ball.x.coerceAtLeast(0f)
        val cy = ball.y.coerceAtLeast(0f)
        val confidence = ball.confidence.coerceIn(0f, 1f)

        // Feed the trust chain at the source: without this stamp,
        // ballTrust() decays to zero and the trusted gate locks the whole
        // contributor stack out no matter what the detectors see.
        try { VisionTrust.stampBall(confidence) } catch (e: Throwable) { android.util.Log.w("BallTelemetryBridge", "stampBall failed", e) }

        val now = android.os.SystemClock.elapsedRealtime()
        val dtMs = now - previousAtMs
        // SPLENDOR_P12_T2_VELOCITY: velocity here is px/frame by this
        // codebase's convention (see MagneticFeetContributor). A publish gap
        // used to force vx=vy=0, a false zero indistinguishable from a
        // stopped ball, while the EWA-smoothed position kept decaying by
        // 0.40/frame. Scale the delta by the nominal frame interval over the
        // real gap instead, so a reappearing ball keeps a truthful
        // per-frame velocity; gaps over 2s are too old to trust at all.
        if (dtMs !in MIN_VELOCITY_GAP_MS..MAX_VELOCITY_GAP_MS) hasPrevious = false
        previousAtMs = now
        val frameScale = if (hasPrevious) (NOMINAL_FRAME_INTERVAL_MS / dtMs.toFloat()).coerceIn(0.05f, 1f) else 0f
        val vx = if (hasPrevious) (cx - previousX) * frameScale else 0f
        val vy = if (hasPrevious) (cy - previousY) * frameScale else 0f

        try {
            TelemetryCoordinator.updateBallMotion(
                cx,
                cy,
                vx,
                vy,
                confidence
            )
            published++
            lastConfidence = confidence
            lastUpdatedMs = System.currentTimeMillis()
        } catch (e: Throwable) { android.util.Log.w("BallTelemetryBridge", "updateBallMotion failed", e) }

        try { VisionTrust.pushMotion(vx, vy) } catch (e: Throwable) { android.util.Log.w("BallTelemetryBridge", "pushMotion failed", e) }

        previousX = cx
        previousY = cy
        hasPrevious = true
    }

    @Synchronized
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
