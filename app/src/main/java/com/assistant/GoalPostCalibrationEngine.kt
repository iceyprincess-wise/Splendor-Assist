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
 * which passes latest()) + the GK log (goalWidth is now logged).
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
    val confidence: Float = 0f
) {
    /** Near post for this goal end; 0f when unanchored. */
    val nearPostX: Float get() = if (!valid) 0f else if (goalEnd == GoalEnd.LEFT) leftPostX else rightPostX
    /** Far post for this goal end; 0f when unanchored. */
    val farPostX: Float get() = if (!valid) 0f else if (goalEnd == GoalEnd.LEFT) rightPostX else leftPostX
}

object GoalPostCalibrationEngine {

    @Volatile private var latestCalibration = GoalPostCalibration(valid = false)
    @Volatile private var lastLogMs: Long = 0L

    fun calibrate(
        detection: GoalDetectionResult,
        captureWidth: Float,
        captureHeight: Float
    ): GoalPostCalibration {
        val width = detection.rightX - detection.leftX
        val valid = detection.detected &&
            detection.rightX > detection.leftX &&
            captureWidth > 0f && captureHeight > 0f &&
            width >= captureWidth * 0.02f && width <= captureWidth * 0.60f &&
            detection.leftX >= 0f && detection.rightX <= captureWidth &&
            detection.topY >= 0f && detection.bottomY <= captureHeight &&
            detection.bottomY > detection.topY &&
            detection.confidence.isFinite() && detection.confidence > 0f
        val end = if (!valid) GoalEnd.NONE
            else if ((detection.leftX + detection.rightX) * 0.5f < captureWidth * 0.5f) GoalEnd.LEFT
            else GoalEnd.RIGHT
        val calibration = if (!valid) GoalPostCalibration(valid = false)
        else GoalPostCalibration(
            valid = true,
            leftPostX = detection.leftX,
            rightPostX = detection.rightX,
            goalWidth = width,
            goalEnd = end,
            confidence = detection.confidence.coerceIn(0f, 1f)
        )
        latestCalibration = calibration
        try {
            val now = System.currentTimeMillis()
            if (now - lastLogMs >= 1000L) {
                lastLogMs = now
                RuntimeLogger.log(
                    "GOAL_POSTS width=${String.format("%.1f", calibration.goalWidth)} " +
                        "left=${String.format("%.1f", calibration.leftPostX)} " +
                        "right=${String.format("%.1f", calibration.rightPostX)} " +
                        "end=${calibration.goalEnd} valid=${calibration.valid}",
                    "GOALKEEPER"
                )
            }
        } catch (_: Throwable) { }
        return calibration
    }

    fun latest(): GoalPostCalibration = latestCalibration

    fun reset() {
        latestCalibration = GoalPostCalibration(valid = false)
        lastLogMs = 0L
    }
}
