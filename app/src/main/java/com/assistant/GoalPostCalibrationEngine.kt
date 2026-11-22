package com.assistant

import com.assistant.diagnostic.RuntimeLogger

/**
 * L2 — Goal-Post Calibration (GoalPostCalibrationEngine).
 *
 * Gap (Q64-4 / Q67-21 / Q-N6-3): the keeper had no anchored posts —
 * FarPostCoverageEngine / NearPostCoverageEngine reported `false` for a
 * whole session, `goalWidth` was never logged, and the detector's output
 * was never anchored to posts.
 *
 * Contract: in = frame + GoalDetector output; out = post positions +
 * goalWidth + handedness (which screen end the goal sits at, and which
 * post is which); caller = VisionCore (after GoalDetector.detect);
 * consumer = FarPost/NearPostCoverageEngine (via GoalkeeperActionRouter,
 * which passes latest()) + the GK log (goalWidth is now logged) +
 * KeeperFeedbackContributor (reads leftPostX/rightPostX live to clamp
 * the blind-keeper target into the calibrated goal span).
 *
 * G4 (Letter G): the near/far-post derived getters and the
 * confidence passthrough were deleted — zero readers anywhere (the
 * coverage engines return Boolean from direction + valid and have no
 * coordinate to aim; a far-post gate would be tautological since valid
 * already implies posts inside the frame). goalWidth/goalEnd/
 * invalidReason are read only by the GOAL_POSTS log line; that line is
 * their designed diagnostic consumer (Batch 6b).
 *
 * A calibration is VALID only when the detected box is sane: detected,
 * right > left, width within [2%, 60%] of the capture width, posts inside
 * the frame, top < bottom. Anything else is an unanchored guess and reads
 * valid=false — post-coverage claims then fall back to unanchored
 * (false), never to a fabricated post.
 *
 * Handedness: the goal centre decides the end (LEFT when centre < half
 * width, else RIGHT). With the goal at the LEFT end the near post is the
 * left post and the far post the right post; mirrored at the RIGHT end.
 *
 * The calibration is a single volatile latest value: VisionCore writes it
 * once per frame, the router reads it. The throttled GOAL_POSTS line is
 * the first live `goalWidth` log (Q67-21).
 */
enum class GoalEnd {
    LEFT,
    RIGHT,
    NONE
}

data class GoalPostCalibration(
    val valid: Boolean,
    val leftPostX: Float = 0f,
    val rightPostX: Float = 0f,
    val goalWidth: Float = 0f,
    val goalEnd: GoalEnd = GoalEnd.NONE,
    /*
     * Batch 6b: WHY this calibration is invalid — the first failing
     * clause, e.g. "not-detected" / "width-too-wide". The v219 session
     * logged 23 invalid vs 2 valid with no way to tell which clause
     * failed, because the GOAL_POSTS line printed only valid=false.
     * Empty when valid. Never a fabricated post — the reason only
     * names the failed check.
     */
    val invalidReason: String = ""
)

object GoalPostCalibrationEngine {

    @Volatile private var latestCalibration = GoalPostCalibration(valid = false)
    @Volatile private var lastLogMs: Long = 0L

    // Item 12 (C12): coast — a single missed frame used to overwrite a
    // valid calibration with invalid, so anchoring flickered with
    // detection noise. Keep the last valid calibration for up to
    // COAST_VALID_MS after it was written; the fresh miss is still
    // logged honestly below.
    @Volatile private var lastValidCalibration = GoalPostCalibration(valid = false)
    @Volatile private var lastValidMs: Long = 0L
    private const val COAST_VALID_MS = 500L

    fun calibrate(
        detection: GoalDetectionResult,
        captureWidth: Float,
        captureHeight: Float
    ): GoalPostCalibration {
        val width = detection.rightX - detection.leftX
        // Batch 6b: name the FIRST failing clause so an invalid line
        // is diagnosable (v219: 23 invalid vs 2 valid, cause unknown).
        // The checks run in the same order as the validity conjunction
        // below, so the reason always names a clause that actually
        // failed; when every clause passes the reason stays empty.
        val invalidReason = when {
            !detection.detected -> "not-detected"
            detection.rightX <= detection.leftX -> "inverted-box"
            !(captureWidth > 0f && captureHeight > 0f) -> "no-capture"
            width < captureWidth * 0.02f -> "width-too-narrow"
            width > captureWidth * 0.60f -> "width-too-wide"
            detection.leftX < 0f || detection.rightX > captureWidth -> "posts-outside-frame-x"
            detection.topY < 0f || detection.bottomY > captureHeight -> "posts-outside-frame-y"
            detection.bottomY <= detection.topY -> "inverted-box-y"
            !detection.confidence.isFinite() || detection.confidence <= 0f -> "no-confidence"
            else -> ""
        }
        val valid = invalidReason.isEmpty()
        val end = if (!valid) GoalEnd.NONE
            else if ((detection.leftX + detection.rightX) * 0.5f < captureWidth * 0.5f) GoalEnd.LEFT
            else GoalEnd.RIGHT
        val calibration = if (!valid) GoalPostCalibration(valid = false, invalidReason = invalidReason)
        else GoalPostCalibration(
            valid = true,
            leftPostX = detection.leftX,
            rightPostX = detection.rightX,
            goalWidth = width,
            goalEnd = end
        )
        val nowMs = System.currentTimeMillis()
        // Item 12 (C12): coast the last valid calibration (see header);
        // the LOG below still prints the fresh miss with its reason.
        val effective = when {
            valid -> {
                lastValidCalibration = calibration
                lastValidMs = nowMs
                calibration
            }
            lastValidCalibration.valid && nowMs - lastValidMs < COAST_VALID_MS ->
                lastValidCalibration
            else -> calibration
        }
        latestCalibration = effective
        try {
            val now = System.currentTimeMillis()
            if (now - lastLogMs >= 1000L) {
                lastLogMs = now
                RuntimeLogger.log(
                    "GOAL_POSTS width=${String.format("%.1f", calibration.goalWidth)} " +
                        "left=${String.format("%.1f", calibration.leftPostX)} " +
                        "right=${String.format("%.1f", calibration.rightPostX)} " +
                        "end=${calibration.goalEnd} valid=${calibration.valid}" +
                        // Batch 6b: the reason an invalid calibration
                        // failed — without it the line cannot say why.
                        (if (!calibration.valid) " reason=${calibration.invalidReason}" else ""),
                    "GOALKEEPER"
                )
            }
        } catch (_: Throwable) { }
        return effective
    }

    fun latest(): GoalPostCalibration = latestCalibration

    fun reset() {
        latestCalibration = GoalPostCalibration(valid = false)
        lastLogMs = 0L
        // Queue item 13: the coast state survived reset — the first
        // invalid calibrate() within 500 ms of the last valid write
        // resurrected the stale valid calibration after a reset.
        lastValidCalibration = GoalPostCalibration(valid = false)
        lastValidMs = 0L
    }
}
