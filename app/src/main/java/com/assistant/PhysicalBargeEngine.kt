package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-M — Physical Barge (engine-ideas §4).
 *
 * Gap (PROVEN, Q68-31): the defence had no physical barge/tap — the
 * channel did not exist. The spec's contract: EngineContribution needs
 * a tap/barge field + terminal support.
 *
 * The channel is built here in the smallest form the terminal can
 * honestly render: EngineContribution gains `barge: Boolean = false`
 * (RuntimeFrame.kt — default false, so all 55 existing contributors
 * are unaffected), and RuntimeDecisionLoop.chooseRequest renders a
 * barge contribution as a SHORT, HARD swipe (duration clamped to
 * <= 20 ms) toward the carrier. The terminal has no tap button to
 * press — it dispatches swipes — so a barge IS a short hard swipe in
 * this terminal, and the flag is what distinguishes it from an
 * ordinary DEFEND drag in the dispatch log and in duration. No new
 * gesture type is invented; the flag + the duration clamp ARE the
 * terminal support.
 *
 * Window (pure): the nearest teammate to the ball is inside contact
 * distance of the carrier AND the ball is carrier-paced (not struck)
 * AND the approach is not from directly behind the carrier's motion
 * (a from-behind barge is a foul, not a barge — see BeastTackleEngine
 * for the shared foul-risk reasoning). PhysicalBargeContributor offers
 * it as a DEFEND-class contribution with barge=true.
 */
data class BargeWindow(
    val open: Boolean,
    val presserId: Int?
)

object PhysicalBargeEngine {

    /** Contact distance: inside this, bodies can touch. */
    const val CONTACT_RADIUS_FRACTION_OF_HEIGHT = 0.06f
    /** Above this ball speed the ball is struck, not carried. */
    const val STRUCK_BALL_SPEED_PX_PER_S = 150f
    /** Approach dot above this = chasing from directly behind. */
    const val FROM_BEHIND_DOT = 0.5f

    fun window(frame: RuntimeFrame): BargeWindow {
        val closed = BargeWindow(false, null)
        if (!frame.trusted || frame.hasBall) return closed
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return closed
        val ballSpeed = hypot(frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond)
        if (!ballSpeed.isFinite() || ballSpeed > STRUCK_BALL_SPEED_PX_PER_S) return closed
        val teammates = frame.entities.filter { it.teammate }
        if (teammates.isEmpty()) return closed
        val radius = CONTACT_RADIUS_FRACTION_OF_HEIGHT * frame.captureHeight
        val presser = teammates.minByOrNull { t -> hypot(t.x - frame.ballX, t.y - frame.ballY) }
            ?: return closed
        val dist = hypot(presser.x - frame.ballX, presser.y - frame.ballY)
        if (dist > radius) return closed
        // From-behind check: approach vector (presser -> carrier) dotted
        // with the carrier's motion. The carrier's motion is the ball
        // velocity (the ball moves with the carrier while dribbling).
        // Dot > 0.5 means the presser is chasing from behind the motion.
        val ax = frame.ballX - presser.x
        val ay = frame.ballY - presser.y
        val aLen = hypot(ax, ay).coerceAtLeast(1f)
        val mLen = hypot(frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond)
        if (mLen > 1f) {
            val dot = (ax / aLen) * (frame.ballVelocityXPerSecond / mLen) +
                (ay / aLen) * (frame.ballVelocityYPerSecond / mLen)
            if (dot > FROM_BEHIND_DOT) return closed
        }
        return BargeWindow(true, presser.id)
    }
}
