package com.assistant.overlay.interceptor

enum class ShotDirection {
    NEAR_POST,
    FAR_POST,
    CENTER,
    CROSS,
    LONG_BALL
}

object ShotDirectionEngine {

    /**
     * Q68-86 (batch 5b — D15: LONG_BALL = trajectory): LONG_BALL was the
     * catch-all else-branch — any zone/threat that missed the four arms
     * was a "long ball", including a static ball with no trajectory at
     * all. A long ball is defined by its trajectory (Ed, §5 D15), so the
     * arm now requires a real one: ballSpeedPxPerFrame > 0 from the live
     * BallTrajectoryPredictor (MotionTracker zeroes it when the ball is
     * not observed). With no trajectory the direction falls back to
     * CENTER (no claim about where the shot is going). No speed threshold
     * is invented — any positive speed counts as a trajectory; whether
     * the ball is travelling TOWARD the goal (not just moving) is a
     * further refinement this signature does not carry yet.
     */
    fun detect(
        zone: ThreatZone,
        threat: ThreatType,
        ballSpeedPxPerFrame: Float = 0f
    ): ShotDirection {

        return when {

            zone == ThreatZone.LEFT &&
            threat.score >= 85 ->
                ShotDirection.FAR_POST

            zone == ThreatZone.RIGHT &&
            threat.score >= 85 ->
                ShotDirection.NEAR_POST

            zone == ThreatZone.BOX &&
            threat.score >= 75 ->
                ShotDirection.CROSS

            zone == ThreatZone.GOAL_AREA &&
            threat.score >= 75 ->
                ShotDirection.CENTER

            ballSpeedPxPerFrame > 0f ->
                ShotDirection.LONG_BALL

            else ->
                ShotDirection.CENTER
        }
    }
}
