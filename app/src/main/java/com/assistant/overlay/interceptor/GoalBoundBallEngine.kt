package com.assistant.overlay.interceptor

/**
 * Item 12 (12c) — Goal-bound ball test (pure, no Android).
 *
 * ROOT CAUSE (PROVEN): the keeper decision never reads ball speed or
 * ball destination. Priority comes from gauge-pixel colour + zone +
 * sliders only, so a fast shot under a low-scoring gauge pixel scores
 * below SAVE (100) and routes to TRACK — the keeper stands still —
 * while ball speed is not an input anywhere on the dive path. Ed's
 * standard: ANY ball speed is a threat; NO speed floor below which the
 * keeper can relax.
 *
 * This engine is the second qualification path (it does NOT re-tune
 * the 100 priority line): when the ball's predicted position will
 * cross the goal line inside the goal span, the ball is goal-bound and
 * the keeper must save it whatever the gauge says. It is also the
 * FIRST live consumer of `BallTrajectoryPredictor.current()`
 * predictedX/predictedY (B letter: they never had a reader).
 *
 * Coordinates are capture pixels; the own goal sits at the BOTTOM of
 * the portrait frame, so a ball heading for our goal moves DOWN
 * (vyPerSecond > 0). The goal span comes from the calibrated posts
 * when valid, else the defensive layer's goal-area span [0.32w, 0.68w].
 * The goal line is the goal box bottom when the goal is detected, else
 * the bottom edge of the frame.
 */
object GoalBoundBallEngine {

    /** Fallback goal span (defensive layer's goal-area span). */
    const val GOAL_AREA_LEFT_FRACTION = 0.32f
    const val GOAL_AREA_RIGHT_FRACTION = 0.68f

    /**
     * @param velocityY ball velocity toward the goal line. Only the SIGN
     *   is read (positive = moving down the portrait frame, toward our
     *   goal), so the unit does not matter — per-frame or per-second.
     */
    fun isGoalBound(
        predictedX: Float,
        predictedY: Float,
        velocityY: Float,
        predictionValid: Boolean,
        goalLeftX: Float,
        goalRightX: Float,
        goalDetected: Boolean,
        goalLineY: Float,
        captureWidth: Float,
        captureHeight: Float
    ): Boolean {
        if (!predictionValid) return false
        if (!predictedX.isFinite() || !predictedY.isFinite() ||
            !velocityY.isFinite()
        ) return false
        // Must be moving toward our goal (down the portrait frame).
        if (velocityY <= 0f) return false
        if (captureWidth <= 0f || captureHeight <= 0f) return false
        val left = if (goalDetected && goalRightX > goalLeftX) goalLeftX
            else captureWidth * GOAL_AREA_LEFT_FRACTION
        val right = if (goalDetected && goalRightX > goalLeftX) goalRightX
            else captureWidth * GOAL_AREA_RIGHT_FRACTION
        if (predictedX < left || predictedX > right) return false
        val lineY = if (goalDetected && goalLineY > 0f) goalLineY else captureHeight
        // The predicted point reaches (or passes) the goal line.
        return predictedY >= lineY
    }
}
