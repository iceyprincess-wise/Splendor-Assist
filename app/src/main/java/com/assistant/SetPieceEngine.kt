package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-A — Set-Piece Engine (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G3): corners, free kicks and penalties
 * had no routine — the keeper and defence used open-play logic during a
 * set piece. This engine detects the dead-ball set piece from the frame
 * alone (no new vision input): a nearly-static ball + a packed goal area.
 *
 * Geometry note: the capture is landscape and the goal sits at an X end
 * (QD-3 / L12 model). All thresholds are fractions of the live capture
 * size, never absolute pixels, so the 288x640 and 1650x720 captures
 * behave the same. Detection is deliberately conservative — a moving
 * ball is never a set piece — so a false positive cannot invent a
 * dead ball during open play.
 *
 * Scope (honest): this engine detects + names the set piece and hands
 * the DEFENSIVE posture to SetPieceContributor (opponent's dead ball
 * near our goal). An attacking set piece (our corner / free kick) is
 * detected and reported but has no new attacking routine here — the
 * existing CrossDeliveryContributor already owns crosses into the box
 * when a lane is viable, and inventing a second attacking routine on
 * top of it would double-offer the same cross.
 */
enum class SetPieceKind { NONE, CORNER, FREE_KICK, PENALTY }

data class SetPieceDetection(
    val kind: SetPieceKind,
    /** True when the dead ball is the OPPONENT's (we do not have it). */
    val defensive: Boolean,
    /** Near-post cover point for the defensive posture. */
    val coverX: Float,
    val coverY: Float
)

object SetPieceEngine {

    /** Ball speed below this is "dead" (px/s in capture space). */
    const val DEAD_BALL_SPEED_PX_PER_S = 25f
    /** Opponents inside this radius of our goal centre count as "packed". */
    const val PACKED_RADIUS_FRACTION_OF_WIDTH = 0.35f
    const val PACKED_MIN_OPPONENTS = 3
    /** Ball within this of a capture corner is a corner kick. */
    const val CORNER_FRACTION_OF_WIDTH = 0.12f
    const val CORNER_FRACTION_OF_HEIGHT = 0.15f
    /** Ball within this of the goal centre is a penalty. */
    const val PENALTY_FRACTION_OF_WIDTH = 0.18f

    fun detect(frame: RuntimeFrame): SetPieceDetection {
        val none = SetPieceDetection(SetPieceKind.NONE, false, 0f, 0f)
        if (!frame.trusted || !frame.goalDetected) return none
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return none
        // A moving ball is open play, never a set piece.
        val ballSpeed = hypot(frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond)
        if (!ballSpeed.isFinite() || ballSpeed > DEAD_BALL_SPEED_PX_PER_S) return none
        // Our goal is the detected goal: the set piece is near OUR goal.
        // (An attacking set piece at the far goal has no detected goal
        // box of ours in view; the goal detector tracks the goal the
        // keeper defends.)
        val goalCx = frame.goalCenterX
        val goalCy = frame.goalCenterY
        val ballToGoal = hypot(frame.ballX - goalCx, frame.ballY - goalCy)
        if (ballToGoal > PACKED_RADIUS_FRACTION_OF_WIDTH * frame.captureWidth) return none
        // Packed box: enough opponents around our goal to be a set piece,
        // not a single attacker dribbling in.
        val packedRadius = PACKED_RADIUS_FRACTION_OF_WIDTH * frame.captureWidth
        val packed = frame.entities.count { e ->
            !e.teammate && hypot(e.x - goalCx, e.y - goalCy) <= packedRadius
        }
        if (packed < PACKED_MIN_OPPONENTS) return none

        val w = frame.captureWidth
        val h = frame.captureHeight
        val nearCornerX = frame.ballX < CORNER_FRACTION_OF_WIDTH * w ||
            frame.ballX > w * (1f - CORNER_FRACTION_OF_WIDTH)
        val nearCornerY = frame.ballY < CORNER_FRACTION_OF_HEIGHT * h ||
            frame.ballY > h * (1f - CORNER_FRACTION_OF_HEIGHT)
        val kind = when {
            nearCornerX && nearCornerY -> SetPieceKind.CORNER
            ballToGoal <= PENALTY_FRACTION_OF_WIDTH * w -> SetPieceKind.PENALTY
            else -> SetPieceKind.FREE_KICK
        }
        // Near-post cover point: the post nearer the ball.
        val nearPostY = if (frame.ballY <= goalCy) frame.goalTopY else frame.goalBottomY
        return SetPieceDetection(
            kind = kind,
            defensive = !frame.hasBall,
            coverX = goalCx,
            coverY = nearPostY
        )
    }
}
