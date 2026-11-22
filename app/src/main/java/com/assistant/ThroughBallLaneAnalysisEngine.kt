package com.assistant

import kotlin.math.*

object ThroughBallLaneAnalysisEngine {

    fun analyze(
        graph: PassingLaneGraph
    ): ThroughBallLaneAnalysis {

        val result = ArrayList<ThroughBallLane>(graph.lanes.size)

        for (lane in graph.lanes) {

            val leadDistance =
                (lane.distance * 0.18f)
                    .coerceIn(15f,120f)

            val confidence =
                (
                    lane.score *
                    (1f - lane.pressure)
                ).coerceIn(0f,1f)

            // B9: direction-aware lead vector. The old lead was a
            // directionless scalar that AGC applied as receiverY −
            // lead — always up-screen, regardless of attack direction
            // (attack is along X) or the receiver's run. The lead now
            // points along the RECEIVER'S RUN when the receiver is
            // really moving (px/s speed ≥ 50, B6 velocity), else along
            // the LANE (passer → receiver). A degenerate lane (zero
            // length, no motion) yields a zero vector — never a −Y
            // fallback. D15: LONG_BALL is not touched here — no
            // long-ball classification exists on this path.
            val (leadX, leadY) = leadVector(lane, leadDistance)

            result += ThroughBallLane(
                lane = lane,
                viable = !lane.blocked && confidence >= 0.35f,
                leadDistance = leadDistance,
                confidence = confidence,
                leadX = leadX,
                leadY = leadY
            )
        }

        result.sortByDescending {
            it.confidence
        }

        return ThroughBallLaneAnalysis(result)
    }

    /**
     * B9 pure lead-vector core (harness-pinned). Receiver run first:
     * when the receiver's px/s velocity (B6) is a real run, the lead
     * follows the run; otherwise it follows the lane. Returns (0,0)
     * when neither direction exists.
     */
    fun leadVector(lane: PassingLane, leadDistance: Float): Pair<Float, Float> {
        val receiverSpeed = kotlin.math.hypot(
            lane.receiver.velocityXPerSecond.toDouble(),
            lane.receiver.velocityYPerSecond.toDouble()
        ).toFloat()
        if (receiverSpeed >= RUN_DIRECTION_MIN_PX_PER_S &&
            lane.receiver.velocityXPerSecond.isFinite() &&
            lane.receiver.velocityYPerSecond.isFinite()
        ) {
            val inv = 1f / receiverSpeed
            return Pair(
                lane.receiver.velocityXPerSecond * inv * leadDistance,
                lane.receiver.velocityYPerSecond * inv * leadDistance
            )
        }
        val dx = lane.receiver.x - lane.passer.x
        val dy = lane.receiver.y - lane.passer.y
        val laneLength = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (!laneLength.isFinite() || laneLength <= 0f) return Pair(0f, 0f)
        return Pair(dx / laneLength * leadDistance, dy / laneLength * leadDistance)
    }

    /** A receiver slower than this is standing, not running a lane. */
    const val RUN_DIRECTION_MIN_PX_PER_S = 50f
}
