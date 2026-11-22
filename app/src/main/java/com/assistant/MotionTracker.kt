package com.assistant

import kotlin.math.atan2

import kotlin.math.hypot

object MotionTracker {

    private var previousX = 0f
    private var previousY = 0f

    private var initialized = false

    // Batch-trajectory (A): the raw per-frame velocity published last
    // frame, kept so a sudden direction change can be recognised as a
    // bounce / deflection. Reset whenever the ball is lost (see below)
    // and by reset().
    private var lastVx = 0f
    private var lastVy = 0f

    private const val LOOK_AHEAD_FRAMES = 6f

    // Batch-trajectory (A): a bounce / deflection is a sudden
    // velocity-DIRECTION change, read from measured positions only. A
    // turn of more than 90 degrees between consecutive per-frame
    // velocities cannot be continuous ball motion at the ~20 fps
    // processed rate — it takes an impact (post / player / ground).
    // Below the speed floor the direction is pixel jitter, not motion,
    // so no flag. No bounce physics is invented: the frame is only
    // marked invalid (see update) and re-anchored from the new velocity.
    // The floor value is a stated threshold, not a measured constant:
    // sub-2 px/frame displacements sit inside detection jitter at the
    // 288x640 capture size.
    private const val DEFLECTION_MIN_SPEED_PX_PER_FRAME = 2f

    /**
     * Test hook (no53 round): resets the previous position and the
     * initialised flag so unit tests start from a known state. No
     * production caller uses it. Tests call it in @Before so JUnit's
     * hash method order can never flip a result.
     */
    fun reset() {
        previousX = 0f
        previousY = 0f
        initialized = false
        lastVx = 0f
        lastVy = 0f
    }

    fun update(
        ball: BallDetectionResult
    ): MotionResult {

        if (!ball.detected || !ball.observed) {

            initialized = false
            lastVx = 0f
            lastVy = 0f
            BallTrajectoryPredictor.update(0f, 0f, 0f, 0f, 0f, 0f, 0f, false)

            return MotionResult(
                0f,
                0f,
                0f,
                0f
            )
        }

        val vx =
            if (initialized) ball.x - previousX else 0f

        val vy =
            if (initialized) ball.y - previousY else 0f

        val speed =
            hypot(vx,vy)

        // Batch-trajectory (A): deflection test. The displacement on
        // this frame straddles the impact when the new velocity points
        // back against the old one, so it mixes pre- and post-bounce
        // motion and must NOT be carried forward as the ball's velocity.
        val deflectionDetected =
            initialized &&
            hypot(lastVx, lastVy) >= DEFLECTION_MIN_SPEED_PX_PER_FRAME &&
            speed >= DEFLECTION_MIN_SPEED_PX_PER_FRAME &&
            (lastVx * vx + lastVy * vy) < 0f

        if (deflectionDetected) {
            // Re-anchor: publish the ball HERE with no velocity and the
            // prediction marked invalid, so no reader trusts a mixed
            // line. The anchor (previousX/Y) still advances to the
            // current position and `initialized` stays true, so the NEXT
            // frame's velocity is pure post-bounce motion and the
            // prediction is valid again from the new line.
            BallTrajectoryPredictor.update(
                ball.x,
                ball.y,
                0f,
                0f,
                ball.x,
                ball.y,
                0f,
                false
            )

            lastVx = vx
            lastVy = vy
            previousX = ball.x
            previousY = ball.y

            return MotionResult(
                velocityX = vx,
                velocityY = vy,
                speed = speed,
                directionRadians = atan2(vy,vx)
            )
        }

        val predictedX =
            ball.x + (vx * LOOK_AHEAD_FRAMES)

        val predictedY =
            ball.y + (vy * LOOK_AHEAD_FRAMES)

        BallTrajectoryPredictor.update(
            ball.x,
            ball.y,
            vx,
            vy,
            predictedX,
            predictedY,
            speed,
            true
        )

        lastVx = vx
        lastVy = vy
        previousX = ball.x
        previousY = ball.y
        initialized = true

        return MotionResult(
            velocityX = vx,
            velocityY = vy,
            speed = speed,
            directionRadians = atan2(vy,vx)
        )
    }
}
