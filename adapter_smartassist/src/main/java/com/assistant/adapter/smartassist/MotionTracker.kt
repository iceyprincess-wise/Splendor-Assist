package com.assistant.adapter.smartassist

import kotlin.math.atan2
import kotlin.math.hypot

object MotionTracker {

    private var previousX = 0f
    private var previousY = 0f

    private var previousSpeed = 0f

    private const val LOOK_AHEAD_FRAMES = 6f

    fun update(
        ball: BallDetectionResult
    ): MotionResult {

        if (!ball.detected) {

            previousSpeed = 0f

            return MotionResult(
                0f,
                0f,
                0f,
                0f
            )
        }

        val vx =
            ball.x - previousX

        val vy =
            ball.y - previousY

        val speed =
            hypot(vx,vy)

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
            speed
        )

        previousX = ball.x
        previousY = ball.y
        previousSpeed = speed

        return MotionResult(
            velocityX = vx,
            velocityY = vy,
            speed = speed,
            directionRadians = atan2(vy,vx)
        )
    }
}
