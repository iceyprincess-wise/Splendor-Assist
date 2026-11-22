package com.assistant

import com.assistant.runtime.RuntimeEntity
import kotlin.math.*

/*
 * Opponent pass-lane interception (queue item 11, F3) — pure.
 *
 * The mirror of DefenderInterceptionPredictionEngine with the teams
 * swapped: THAT engine scores OPPONENT interceptors on OUR passing
 * lanes (offensive risk — which of our passes the opponent cuts). This
 * one scores OUR controlled defender on the OPPONENT's lanes (carrier
 * -> each opponent receiver at his predicted position), so the
 * defender pre-moves to where the opponent's pass is GOING before it
 * is played — the don't-wait anticipation, including through-pass
 * lanes: a receiver running into space behind our line has a predicted
 * position there, and his lane IS the through lane. No separate
 * through-lane analysis is needed.
 *
 * The geometry is the existing engine's, reused exactly (projection
 * onto the lane, closest point, ball time vs defender time, risk
 * mapping); only the teams and the inputs change. Two deliberate
 * differences, both documented here, not hidden:
 *   - NO lane.pressure blend: no measured pressure signal exists for
 *     opponent lanes, so the risk is the raw time-difference risk
 *     (the existing engine blends risk x 0.75 + lane.pressure x 0.25).
 *   - NO +0.25f velocity nudge on the intercept point: that nudge is
 *     0.25 px/frame (a quarter-frame, sub-pixel per frame at 20 fps);
 *     omitting it changes nothing measurable.
 *
 * The controlled defender is the gate: the engine evaluates reachability
 * FOR HIM (the app drives one player; sending him on another man's run
 * is a worse run). The contributor additionally requires him to be the
 * nearest user player to the intercept point — "the nearest player
 * intercepts it".
 */
object OpponentPassLaneEngine {

    // The same physical estimates DefenderInterceptionPredictionEngine
    // uses. Its constants are ESTIMATES, accuracy UNVERIFIED (6d note)
    // — reused here, never re-tuned: average pass velocity, defender
    // sprint velocity, reaction latency, safe pass buffer.
    const val ESTIMATED_BALL_SPEED_PX_PER_SEC = 1450f
    const val DEFENDER_MAX_SPEED_PX_PER_SEC = 420f
    const val DEFENDER_REACTION_LATENCY_SEC = 0.12f
    const val SAFE_PASS_BUFFER_SEC = 0.08f

    // Receiver prediction horizon: 0.3 s = 6 frames at the 20 fps
    // processed rate — the repo's prediction horizon (MotionTracker's
    // LOOK_AHEAD_FRAMES = 6f), expressed in seconds because the receiver
    // velocity is per-second.
    const val RECEIVER_PREDICTION_SEC = 0.3f

    // Risk threshold — the existing engine's predictedIntercept bar.
    const val INTERCEPT_RISK_THRESHOLD = 0.50f

    // The alignment bonus applies only when the defender is really
    // moving: the existing engine tests |v| > 10 px/frame (TrackedPlayer
    // per-frame velocity); here the velocity is per-second, so the same
    // test at the 20 fps processed rate is |v| > 200 px/s.
    const val MOVING_SPEED_PX_PER_SEC = 200f

    data class LaneIntercept(
        val receiverId: Int,
        val interceptX: Float,
        val interceptY: Float,
        val risk: Float,
        val predictedIntercept: Boolean,
        val reachable: Boolean,
        val defenderTimeSec: Float,
        val ballTimeSec: Float
    )

    fun analyze(
        carrierX: Float, carrierY: Float,
        receivers: List<RuntimeEntity>,
        defender: RuntimeEntity
    ): List<LaneIntercept> {
        if (receivers.isEmpty()) return emptyList()
        val result = ArrayList<LaneIntercept>(receivers.size)
        for (receiver in receivers) {
            // The receiver's destination, not his current spot: where the
            // pass is GOING (RunPredictionEngine predicts only user-team
            // runs, so opponent receivers have no predictor until now).
            val predX = receiver.x + receiver.velocityXPerSecond * RECEIVER_PREDICTION_SEC
            val predY = receiver.y + receiver.velocityYPerSecond * RECEIVER_PREDICTION_SEC
            val laneDx = predX - carrierX
            val laneDy = predY - carrierY
            val laneDistance = hypot(laneDx.toDouble(), laneDy.toDouble()).toFloat()
            if (laneDistance <= 0f) continue

            val pdX = defender.x - carrierX
            val pdY = defender.y - carrierY
            val dotProduct = (pdX * laneDx) + (pdY * laneDy)
            val projectionFactor = (dotProduct / (laneDistance * laneDistance)).coerceIn(0f, 1f)
            val closestPointX = carrierX + (projectionFactor * laneDx)
            val closestPointY = carrierY + (projectionFactor * laneDy)
            val ballTravelDistance = projectionFactor * laneDistance
            val distToIntercept = hypot(
                (closestPointX - defender.x).toDouble(),
                (closestPointY - defender.y).toDouble()
            ).toFloat()

            val timeForBallToReachC = ballTravelDistance / ESTIMATED_BALL_SPEED_PX_PER_SEC

            var directionAlignmentBonus = 0f
            if (distToIntercept > 0f) {
                val velMagnitude = hypot(
                    defender.velocityXPerSecond.toDouble(),
                    defender.velocityYPerSecond.toDouble()
                ).toFloat()
                if (velMagnitude > MOVING_SPEED_PX_PER_SEC) {
                    val alignment = ((defender.velocityXPerSecond * (closestPointX - defender.x)) +
                        (defender.velocityYPerSecond * (closestPointY - defender.y))) /
                        (velMagnitude * distToIntercept)
                    directionAlignmentBonus = (alignment * 0.15f).coerceIn(-0.1f, 0.2f)
                }
            }

            val defenderTimeToC = (distToIntercept / DEFENDER_MAX_SPEED_PX_PER_SEC) +
                DEFENDER_REACTION_LATENCY_SEC - directionAlignmentBonus
            val timeDifference = timeForBallToReachC - defenderTimeToC
            val risk = when {
                timeDifference >= SAFE_PASS_BUFFER_SEC -> 1.0f
                timeDifference < -0.8f -> 0.0f
                else -> ((timeDifference + 0.8f) / (SAFE_PASS_BUFFER_SEC + 0.8f)).coerceIn(0.0f, 1.0f)
            }

            result.add(
                LaneIntercept(
                    receiverId = receiver.id,
                    interceptX = closestPointX,
                    interceptY = closestPointY,
                    risk = risk,
                    predictedIntercept = risk >= INTERCEPT_RISK_THRESHOLD,
                    // Reachable = the defender arrives with the full safe
                    // buffer to spare (risk 1.0): the narrow confirmed
                    // gate — a pre-move offered only when he gets there
                    // first, not when he merely contests it.
                    reachable = risk >= 1.0f,
                    defenderTimeSec = defenderTimeToC,
                    ballTimeSec = timeForBallToReachC
                )
            )
        }
        result.sortWith(
            compareByDescending<LaneIntercept> { it.risk }.thenBy { it.defenderTimeSec }
        )
        return result
    }
}
