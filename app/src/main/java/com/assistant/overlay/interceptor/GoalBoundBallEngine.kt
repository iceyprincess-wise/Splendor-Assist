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
     * Max warning window for the keeper-side line extrapolation, in
     * processed frames. 12 frames is ~600 ms at the 20 fps processed
     * rate: a shot released further from the line than this is not
     * goal-bound YET — the keeper waits for the next frame instead of
     * committing a dive a second early on a ball that may still be a
     * pass. TUNABLE, not a proven tactical boundary; phone logs
     * (GK_ZONE_SCAN ttlFrames) validate or move it.
     */
    const val KEEPER_GOALBOUND_MAX_FRAMES = 12f

    /**
     * Curve model (R2/curved-shot keeper fix, 2026-11-24): the
     * largest lateral acceleration trusted as a real bend, in
     * px/frame^2. A finesse curl bends ~0.5-1 px/frame^2; detection
     * jitter alone can fake ~2 on a single frame, so the tracker
     * averages over two frames and this clamp is the second wall.
     * TUNABLE, not a measured constant; phone logs (GK_ZONE_SCAN
     * ax) validate or move it.
     */
    const val CURVE_ACCEL_MAX_PX_PER_FRAME_SQ = 2f

    /**
     * Curve model: |acceleration| below this is treated as no bend
     * (straight). Sub-0.5 bends move the crossing by <25 px even at
     * the 12-frame horizon and sit inside detection jitter.
     */
    const val CURVE_ACCEL_DEADBAND_PX_PER_FRAME_SQ = 0.5f

    /**
     * Curved extrapolation: x(t) = x + vx*t + 0.5*ax*t^2, with the
     * acceleration clamped to +/- [CURVE_ACCEL_MAX_PX_PER_FRAME_SQ]
     * and deadbanded below [CURVE_ACCEL_DEADBAND_PX_PER_FRAME_SQ].
     * The single choke point for the curve policy: every consumer
     * (goalBoundToLine, the dive re-aim, the dive promotion) reads
     * the bend through here, so the linear path behaves exactly as
     * before when there is no bend. Fail-open: a non-finite
     * acceleration, position, velocity or horizon returns the
     * linear term (ax = 0) or the current position.
     */
    fun curvedXAt(
        currentX: Float,
        velocityX: Float,
        accelerationX: Float,
        frames: Float
    ): Float {
        if (!currentX.isFinite() || !velocityX.isFinite() || !frames.isFinite()) return currentX
        val ax = if (!accelerationX.isFinite()) 0f
            else accelerationX.coerceIn(-CURVE_ACCEL_MAX_PX_PER_FRAME_SQ, CURVE_ACCEL_MAX_PX_PER_FRAME_SQ)
        val axTrusted =
            if (ax in -CURVE_ACCEL_DEADBAND_PX_PER_FRAME_SQ..CURVE_ACCEL_DEADBAND_PX_PER_FRAME_SQ) 0f else ax
        return currentX + velocityX * frames + 0.5f * axTrusted * frames * frames
    }

    /**
     * Result of the keeper-side goal-bound test: the verdict plus the
     * geometry the log needs to tell a late line from a late
     * prediction. [blockReason] is empty when [goalBound] is true,
     * else the first failing check: "invalid-pred" / "non-finite" /
     * "not-moving-down" / "no-capture" / "past-line-outside" /
     * "beyond-horizon" / "outside-posts".
     */
    data class GoalBoundResult(
        val goalBound: Boolean,
        val goalLineY: Float,
        val predictedXAtLine: Float,
        val timeToLineFrames: Float,
        val blockReason: String
    )

    /**
     * Keeper-side goal-bound test (AI keeper fix, 2026-11-24).
     *
     * The old [isGoalBound] tests the FIXED 6-frame prediction
     * against the frame bottom as the goal line, so goalBound flips
     * true only 0–4 frames before the ball leaves the frame — no
     * time to dive. This test extrapolates the ball to the REAL goal
     * line (the detected goal box bottom when the goal is detected,
     * else the frame bottom) and reports the crossing point and the
     * time to reach it, so the keeper can commit ~600 ms out
     * instead of ~100 ms.
     *
     * Velocity is px per PROCESSED frame (MotionTracker: the
     * per-frame displacement), so time-to-line comes out in frames.
     * The horizon is capped at [KEEPER_GOALBOUND_MAX_FRAMES]:
     * further out than that, the ball is not goal-bound yet.
     *
     * Curve fix (2026-11-24): [accelerationX]/[accelerationY] are
     * the tracker's two-frame-averaged lateral acceleration
     * (MotionTracker). The crossing X is the curved extrapolation
     * ([curvedXAt]) — a curling ball's true crossing is further
     * toward the far post than the straight-line tangent says.
     * timeToLine itself stays linear (from vy only): the bend is
     * lateral, and solving the quadratic for the time would risk
     * instability for a second-order effect. [accelerationY] is
     * accepted for symmetry and currently unused, for the same
     * reason. With no bend the result is byte-identical to the
     * linear test.
     *
     * Edge parity with [isGoalBound]: a ball already at/past the
     * line still reads goal-bound when it sits inside the posts —
     * the old test had the same property (predictedY past the line),
     * so this keeps behaviour, it does not invent a new one.
     */
    fun goalBoundToLine(
        currentX: Float,
        currentY: Float,
        velocityX: Float,
        velocityY: Float,
        predictionValid: Boolean,
        goalLeftX: Float,
        goalRightX: Float,
        goalDetected: Boolean,
        goalBottomY: Float,
        captureWidth: Float,
        captureHeight: Float,
        accelerationX: Float = 0f,
        accelerationY: Float = 0f
    ): GoalBoundResult {
        val lineY =
            if (goalDetected && goalBottomY > 0f && goalBottomY <= captureHeight) goalBottomY
            else captureHeight
        val left = if (goalDetected && goalRightX > goalLeftX) goalLeftX
            else captureWidth * GOAL_AREA_LEFT_FRACTION
        val right = if (goalDetected && goalRightX > goalLeftX) goalRightX
            else captureWidth * GOAL_AREA_RIGHT_FRACTION
        if (!predictionValid) return GoalBoundResult(false, lineY, currentX, -1f, "invalid-pred")
        if (!currentX.isFinite() || !currentY.isFinite() ||
            !velocityX.isFinite() || !velocityY.isFinite()
        ) return GoalBoundResult(false, lineY, currentX, -1f, "non-finite")
        if (velocityY <= 0f) return GoalBoundResult(false, lineY, currentX, -1f, "not-moving-down")
        if (captureWidth <= 0f || captureHeight <= 0f) return GoalBoundResult(false, lineY, currentX, -1f, "no-capture")
        val timeToLine = (lineY - currentY) / velocityY
        val lineX = curvedXAt(currentX, velocityX, accelerationX, timeToLine.coerceAtLeast(0f))
        if (timeToLine < 0f) {
            return if (lineX in left..right) GoalBoundResult(true, lineY, lineX, timeToLine, "")
            else GoalBoundResult(false, lineY, lineX, timeToLine, "past-line-outside")
        }
        if (timeToLine > KEEPER_GOALBOUND_MAX_FRAMES) {
            return GoalBoundResult(false, lineY, lineX, timeToLine, "beyond-horizon")
        }
        if (lineX < left || lineX > right) {
            return GoalBoundResult(false, lineY, lineX, timeToLine, "outside-posts")
        }
        return GoalBoundResult(true, lineY, lineX, timeToLine, "")
    }

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
