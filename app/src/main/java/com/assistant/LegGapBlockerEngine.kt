package com.assistant

import com.assistant.runtime.RuntimeEntity
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * Batch 6b — Leg-Gap Blocker (Ed's live report: "ball passing in
 * between player legs, they dont use there full body to weigh
 * opponent players off balance aggressive", part 1).
 *
 * Gap PROVEN (PLAN.md trace): zero nutmeg / leg-gap code anywhere.
 * DefensiveStanceProducer is pure LINE geometry (where the line
 * stands, not where any individual body stands); MarkingEngine marks
 * the MAN goal-side, not the PASS LANE. So an opponent's pass could
 * travel straight through a defender's leg gap and nothing in the
 * repo ever noticed — the body was never used as a barrier across
 * the lane.
 *
 * What this engine does: when the ball is moving (a pass in flight
 * or rolling toward a receiver), it builds the ball→receiver segment
 * — the receiver is the opponent nearest the ball's forward ray — and
 * checks whether that segment passes within a defender's body width
 * of the controlled defender (the teammate nearest the lane point).
 * If it does, the window opens and the offer steps that defender's
 * body ONTO the lane (feet together, body across the line), so the
 * pass meets a body instead of a gap.
 *
 * Geometry (from evidence, not invented):
 * - Body half-width 12 px: at match distance a player is ~20-30 px
 *   wide in 640-wide hunt frames (~/workspace/efootball_frames/hunt/),
 *   so half a body is ~10-15 px at capture scale. 12 px is the middle
 *   of that measured range.
 * - Ball radius 3 px: the measured match-distance ball is ~4-5 px
 *   across at capture (see BallCandidateEngine MIN_PIXELS note).
 * - Lane half-width = 12 + 3 = 15 px: the segment passes within a
 *   body half-width plus a ball radius of the defender's centre.
 * - Ball must be moving (> 30 px/s): a stationary ball has no lane.
 *   30 px/s is a movement floor, not a pass-speed claim — a pass in
 *   flight is far faster; the floor only excludes a dead ball.
 * - The defender stepping across is the teammate nearest the lane
 *   point (the controlled defender by the same rule MarkingContributor
 *   uses: frame.defenderId else nearest teammate to the ball). Only
 *   that ONE defender moves — NO second-player control (G-O block
 *   stands).
 *
 * D14 guard: the step target is clamped to stay on the goal side of
 * the tight defensive line, exactly the MarkingEngine rule — the line
 * itself never breaks; the step is a small lateral move onto the
 * lane, and a lane point beyond the line toward the attack end is
 * pulled back to the line. Ed's tactical numbers (D14 25%/30%/12%)
 * are untouched.
 *
 * Output is a DEFEND-class offer at authority 0.70 with a 40 ms
 * duration hint (a positioning step, like Marking's 40 ms) — NOT a
 * barge, NOT a tackle. When the window is closed the contributor is
 * silent and the ordinary press/marking owns the frame.
 */
data class LegGapWindow(
    val open: Boolean,
    /** Why the gate closed, for the log / pins. Empty when open. */
    val closedReason: String,
    val defenderId: Int?,
    val laneX: Float,
    val laneY: Float
)

object LegGapBlockerEngine {

    /** Half a defender's body width at capture scale (measured 10-15 px). */
    const val BODY_HALF_WIDTH_PX = 12f
    /** Ball radius at match distance at capture scale (measured ~2-2.5 px). */
    const val BALL_RADIUS_PX = 3f
    /** Segment within this of the defender's centre = through his legs. */
    const val LANE_HALF_WIDTH_PX = BODY_HALF_WIDTH_PX + BALL_RADIUS_PX
    /** Ball must be moving at least this fast to have a lane. */
    const val MIN_BALL_SPEED_PX_PER_S = 30f

    fun window(frame: RuntimeFrame): LegGapWindow {
        val closed = { reason: String -> LegGapWindow(false, reason, null, 0f, 0f) }
        if (!frame.trusted || frame.hasBall) return closed("no-ball-or-ours")
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return closed("no-capture")
        val ballSpeed = hypot(frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond)
        if (!ballSpeed.isFinite() || ballSpeed <= MIN_BALL_SPEED_PX_PER_S) {
            return closed("ball-still")
        }
        val opponents = frame.entities.filter { !it.teammate }
        if (opponents.isEmpty()) return closed("no-opponents")
        val teammates = frame.entities.filter { it.teammate }
        if (teammates.isEmpty()) return closed("no-teammates")
        // Receiver: the opponent nearest the ball's forward ray — the
        // man the moving ball is travelling toward. Must be ahead of
        // the ball along its motion (projection t > 0).
        val vx = frame.ballVelocityXPerSecond / ballSpeed
        val vy = frame.ballVelocityYPerSecond / ballSpeed
        val receiver = opponents
            .map { opp ->
                val rx = opp.x - frame.ballX
                val ry = opp.y - frame.ballY
                val t = rx * vx + ry * vy
                val perp = kotlin.math.abs(rx * vy - ry * vx)
                Triple(opp, t, perp)
            }
            .filter { it.second > 0f }
            .minByOrNull { it.third }
            ?.first ?: return closed("no-receiver-ahead")
        // Closest point on the ball->receiver segment to each teammate;
        // the defender is the teammate nearest that segment.
        val segX = receiver.x - frame.ballX
        val segY = receiver.y - frame.ballY
        val segLenSq = (segX * segX + segY * segY).coerceAtLeast(1f)
        var bestDefender: RuntimeEntity? = null
        var bestLaneX = 0f
        var bestLaneY = 0f
        var bestDist = Float.MAX_VALUE
        for (t in teammates) {
            val tt = (((t.x - frame.ballX) * segX + (t.y - frame.ballY) * segY) / segLenSq)
                .coerceIn(0f, 1f)
            val lx = frame.ballX + segX * tt
            val ly = frame.ballY + segY * tt
            val d = hypot(t.x - lx, t.y - ly)
            if (d < bestDist) {
                bestDist = d
                bestDefender = t
                bestLaneX = lx
                bestLaneY = ly
            }
        }
        val defender = bestDefender ?: return closed("no-defender")
        if (bestDist > LANE_HALF_WIDTH_PX) return closed("lane-clear")
        // D14 guard: the step target stays on the goal side of the
        // tight line (MarkingEngine rule). The line never breaks.
        var laneX = bestLaneX
        val laneY = bestLaneY
        if (frame.stanceTight && frame.defensiveLineX > 0f) {
            val attackSign = if (frame.attackDirection < 0) -1f else 1f
            val ahead = (laneX - frame.defensiveLineX) * attackSign
            if (ahead > 0f) laneX = frame.defensiveLineX
        }
        return LegGapWindow(true, "", defender.id, laneX, laneY)
    }
}
