package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.atomic.AtomicLong

object TelemetryCoordinator {

    // No68-E2-D6: the telemetry log is a forensic trail, not a frame
    // record. RuntimeLogger.telemetry() builds a Throwable and walks the
    // stack on the calling thread on every call, and the ball + GK
    // updaters run once per vision frame (~30 lines/s combined). The
    // repository update below stays per-frame; only the log line is
    // throttled to 1 Hz per updater.
    private const val TELEMETRY_LOG_MIN_INTERVAL_MS = 1000L
    private val lastBallLogMs = AtomicLong(0L)
    private val lastGoalkeeperLogMs = AtomicLong(0L)

    private fun logThrottled(lastLogMs: AtomicLong, details: String) {
        val now = System.currentTimeMillis()
        val prev = lastLogMs.get()
        if (now - prev < TELEMETRY_LOG_MIN_INTERVAL_MS) return
        if (!lastLogMs.compareAndSet(prev, now)) return
        RuntimeLogger.telemetry(details)
    }

    // T-7 (Phase T): updatePlayerMotion DELETED - zero callers repo-wide
    // (the producer is QE2-2, never built). The fields playerVelocity /
    // opponentDistance STAY: LiveVectorResolver reads them live, so they
    // sit at their no-data defaults until a producer exists (QE2-2
    // carried - wire a real producer or delete the fields + the
    // duration term; a gameplay-behaviour decision for Ed).

    fun updateBallMotion(
        x: Float,
        y: Float,
        velocityX: Float,
        velocityY: Float,
        confidence: Float
    ) {
        // No68-E2-D7: the GK updater guarded confidence with isFinite()
        // but this one did not (NaN.coerceIn() stays NaN), and neither
        // guarded x/y/velocity — a NaN here poisons every consumer of
        // the repository. Non-finite inputs become 0.
        // No68-E2-D5: atomic transform — the old current()+copy()+update()
        // lost the other updater's fields when ball and GK writes raced.
        val safeX = if (x.isFinite()) x else 0f
        val safeY = if (y.isFinite()) y else 0f
        val safeVx = if (velocityX.isFinite()) velocityX else 0f
        val safeVy = if (velocityY.isFinite()) velocityY else 0f
        val safeConfidence = if (confidence.isFinite()) confidence.coerceIn(0f, 1f) else 0f
        TelemetryRepository.update { current ->
            current.copy(
                ballX = safeX,
                ballY = safeY,
                ballVelocityX = safeVx,
                ballVelocityY = safeVy,
                confidence = safeConfidence,
                timestamp = System.currentTimeMillis(),
                // B2 / Q68-54: the ball writer stamps only the ball field.
                ballTimestampMs = System.currentTimeMillis(),
                captureWidth = com.assistant.vision.CameraProfile.captureWidth,
                captureHeight = com.assistant.vision.CameraProfile.captureHeight
            )
        }

        logThrottled(lastBallLogMs, "ball=($safeX,$safeY) velocity=($safeVx,$safeVy)")
    }

    fun updateGoalkeeperPosition(
        x: Float,
        y: Float,
        confidence: Float
    ) {
        // No68-E2-D8: VisionCore calls this every cycle, including the
        // invisible (0,0,0) publish — and the old code stamped the
        // shared timestamp on every call, so the timestamp never aged
        // and the FrameAssembler / LiveVectorResolver freshness gate
        // (MAX_TELEMETRY_AGE_MS) was vacuous while vision ran. Only a
        // real detection refreshes freshness now; the invisible publish
        // still zeroes the position/confidence so a stale keeper cannot
        // read as tracked. (P12_T2's intent — any REAL telemetry write
        // refreshes freshness — is preserved.)
        val safeX = if (x.isFinite()) x else 0f
        val safeY = if (y.isFinite()) y else 0f
        val safeConfidence = if (confidence.isFinite()) confidence.coerceIn(0f, 1f) else 0f
        val realDetection = safeConfidence > 0f
        TelemetryRepository.update { current ->
            current.copy(
                goalkeeperX = safeX,
                goalkeeperY = safeY,
                timestamp = if (realDetection) System.currentTimeMillis() else current.timestamp,
                captureWidth = com.assistant.vision.CameraProfile.captureWidth,
                captureHeight = com.assistant.vision.CameraProfile.captureHeight
            )
        }

        logThrottled(lastGoalkeeperLogMs, "goalkeeper=($safeX,$safeY)")
    }
}
