package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-D — Goal-Kick / Restart Engine (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G15): restarts had no routine — the app
 * treated a dead ball like open play. No restart state existed in
 * RuntimeFrame / Phase3WorldState.
 *
 * Detection from the frame alone: a nearly-static ball in a restart
 * zone. Zones are fractions of the live capture (never absolute px):
 *  - GOAL_KICK: ball static within our goal area (near our goal centre).
 *  - THROW_IN: ball static hugging a touchline (top/bottom capture edge).
 *  - KICKOFF: ball static on the centre spot.
 * Everything else is OPEN_PLAY. Only OUR restarts (hasBall) get a
 * routine — an opponent's restart is their ball, and the defensive
 * posture already owns that frame (BallPress / marking).
 *
 * Routine: build up through the B8 passing target (frame.passTarget —
 * the passing-graph receiver, not the crossing lane). No new target
 * invention: if there is no passing target, there is no restart offer.
 */
enum class RestartKind { OPEN_PLAY, GOAL_KICK, THROW_IN, KICKOFF }

data class RestartDetection(val kind: RestartKind)

object RestartEngine {

    const val DEAD_BALL_SPEED_PX_PER_S = 25f
    const val GOAL_AREA_FRACTION_OF_WIDTH = 0.20f
    const val TOUCHLINE_FRACTION_OF_HEIGHT = 0.06f
    const val CENTRE_SPOT_FRACTION_OF_WIDTH = 0.05f
    const val CENTRE_SPOT_FRACTION_OF_HEIGHT = 0.08f

    fun detect(frame: RuntimeFrame): RestartDetection {
        if (!frame.trusted || !frame.hasBall) return RestartDetection(RestartKind.OPEN_PLAY)
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) {
            return RestartDetection(RestartKind.OPEN_PLAY)
        }
        val ballSpeed = hypot(frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond)
        if (!ballSpeed.isFinite() || ballSpeed > DEAD_BALL_SPEED_PX_PER_S) {
            return RestartDetection(RestartKind.OPEN_PLAY)
        }
        val w = frame.captureWidth
        val h = frame.captureHeight
        val cx = w * 0.5f
        val cy = h * 0.5f
        // Own goal end: opposite the attack direction; the detected goal
        // box is the keeper's goal, so use it when present.
        val ownGoalX = if (frame.goalDetected) frame.goalCenterX
            else if (frame.attackDirection < 0) w else 0f
        val ownGoalY = if (frame.goalDetected) frame.goalCenterY else cy
        val kind = when {
            hypot(frame.ballX - ownGoalX, frame.ballY - ownGoalY) <=
                GOAL_AREA_FRACTION_OF_WIDTH * w -> RestartKind.GOAL_KICK
            frame.ballY <= TOUCHLINE_FRACTION_OF_HEIGHT * h ||
                frame.ballY >= h * (1f - TOUCHLINE_FRACTION_OF_HEIGHT) -> RestartKind.THROW_IN
            hypot(frame.ballX - cx, frame.ballY - cy) <=
                CENTRE_SPOT_FRACTION_OF_WIDTH * w + CENTRE_SPOT_FRACTION_OF_HEIGHT * h -> RestartKind.KICKOFF
            else -> RestartKind.OPEN_PLAY
        }
        return RestartDetection(kind)
    }
}
