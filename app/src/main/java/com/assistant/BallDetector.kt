package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*
import com.assistant.diagnostic.RuntimeLogger




























object BallDetector {

    private const val MIN_CANDIDATE_CONFIDENCE = 0.12f
    private const val COAST_FRAMES = 4  // PHASE4: 15fps — 6 frames=400ms coast too long; 4=266ms

    private var lastBallX = 0f
    private var lastBallY = 0f
    private var lastConfidence = 0f

    private var initialized = false
    private var lostFrames = 0

    /**
     * Resets the smoothed ball state and the coast counter so unit tests
     * start from a known state (no53 round). Production caller:
     * OverlayService.abandonWedgedVision() — when a wedged vision
     * generation is abandoned, the smoothed position and coast budget
     * belong to the dead generation and must not pull the next one.
     * Tests call it in @Before so JUnit's hash method order can never
     * flip a result.
     */
    fun reset() {
        lastBallX = 0f
        lastBallY = 0f
        lastConfidence = 0f
        initialized = false
        lostFrames = 0
    }

    /*
     * Batch 6b: the last locked-on ball position, for
     * BallCandidateEngine's temporal prior. Null until the detector has
     * locked on (initialized) — VisionCore passes null then, and
     * select() runs exactly as before. The coasted position is NOT
     * returned: after COAST_FRAMES of loss the prior must die with the
     * lock, or a stale position would keep attracting blobs.
     */
    fun lastKnownPosition(): Pair<Float, Float>? =
        if (initialized) Pair(lastBallX, lastBallY) else null

    fun detect(
        candidate: BallCandidate?
    ): BallDetectionResult {

        if (candidate == null || !candidate.score.isFinite() || !candidate.centerX.isFinite() ||
            !candidate.centerY.isFinite() || !candidate.radius.isFinite() || candidate.radius <= 0f || candidate.score < MIN_CANDIDATE_CONFIDENCE) {
            lostFrames++
            if (lostFrames > COAST_FRAMES) {
                // LOGGAPS: lock lost — log once, on the transition
                // (initialized is still true here; later lost frames
                // have it false already).
                if (initialized) {
                    try { RuntimeLogger.log("BALL_LOST lockLost lostFrames=$lostFrames lastX=$lastBallX lastY=$lastBallY lastConf=$lastConfidence", "BALL") } catch (_: Throwable) {}
                }
                initialized = false
                lastBallX = 0f
                lastBallY = 0f
                lastConfidence = 0f
                return BallDetectionResult(
                    detected = false,
                    x = 0f, y = 0f, confidence = 0f
                )
            }
            if (initialized) {
                // LOGGAPS: coast start — first lost frame of this episode
                // (lostFrames == 1; a later lost frame is already coasting).
                if (lostFrames == 1) {
                    try { RuntimeLogger.log("BALL_COAST start lostFrames=1 lastX=$lastBallX lastY=$lastBallY lastConf=$lastConfidence", "BALL") } catch (_: Throwable) {}
                }
                val coastFraction = 1f - (lostFrames.toFloat() / COAST_FRAMES)
                val coastConfidence = (lastConfidence * coastFraction).coerceIn(0f, 1f)
                return BallDetectionResult(
                    detected = true,
                    x = lastBallX, y = lastBallY,
                    confidence = coastConfidence, observed = false
                )
            }
            return BallDetectionResult(
                detected = false,
                x = 0f, y = 0f, confidence = 0f
            )
        }

        lostFrames = 0

        // PHASE4: 15fps EWA — ball moves more between frames, new position must dominate
        // Was 0.55(old)/0.45(new) → lagged badly at 15fps
        // Now 0.40(old)/0.60(new) → tracks fast movement, still smooths noise
        val filteredX =
            if (initialized)
                lastBallX * 0.40f + candidate.centerX * 0.60f
            else
                candidate.centerX

        val filteredY =
            if (initialized)
                lastBallY * 0.40f + candidate.centerY * 0.60f
            else
                candidate.centerY

        val filteredConfidence =
            if (initialized)
                lastConfidence * 0.40f + candidate.score * 0.60f
            else
                candidate.score

        initialized = true

        lastBallX = filteredX
        lastBallY = filteredY
        lastConfidence = filteredConfidence

        return BallDetectionResult(
            detected = true,
            x = filteredX,
            y = filteredY,
            confidence = filteredConfidence.coerceIn(0f, 1f)
        )
    }
}

/* ========
BallOverlay
======== */
