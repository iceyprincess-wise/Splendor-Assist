package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-N — Beast tackling / rough play engine (engine-ideas §7, Ed D11).
 *
 * Ed is mainly after "beast" players: tackle aggressively, play
 * football roughly, foul opponent players who obstruct or are an
 * obstacle, to create space / build up against him.
 *
 * The spec (§7) is explicit about the limit: the engine needs a RISK
 * GATE — a foul that earns a card is a loss. So the beast window opens
 * ONLY when all of these hold:
 *  1. The opponent has the ball and is shielding / dribbling slowly in
 *     the middle third (ball carrier-paced, not struck).
 *  2. Our nearest teammate (the presser) is inside beast range.
 *  3. RISK GATE — none of the card-risk conditions hold:
 *     - from behind (approach dot with the carrier's motion > 0.5 —
 *       chasing from behind is a foul, not a tackle);
 *     - last man (no teammate between the carrier and our goal — a
 *       foul there is a red-card / free-kick disaster);
 *     - inside our own box (a foul there is a penalty — the carrier
 *       must be outside 0.25 * captureWidth of our goal centre).
 *  4. D14 TIGHT-LINE GUARD — the presser is the designated presser
 *     (nearest teammate to the ball) AND the carrier is AHEAD of our
 *     tight defensive line toward the attack end (or the line is
 *     unknown and the carrier is in the middle third, not behind the
 *     line). The line itself never breaks: holders stay, only the
 *     presser goes. This is how beast tackling is verified not to break
 *     the tight-defence long-ball-counter pattern — the guard is a
 *     precondition of the window, pinned in the harness (a carrier
 *     behind the line never opens the window).
 *
 * Output is a DEFEND-class offer (BeastTackleContributor) at authority
 * 0.90 with a 20 ms duration hint — a hard tackle, not a jog. When the
 * gate closes, the contributor is silent and the ordinary press
 * (BallPress + TackleTiming) owns the frame.
 */
data class BeastWindow(
    val open: Boolean,
    /** Why the gate closed, for the log / pins. Empty when open. */
    val closedReason: String,
    val presserId: Int?
)

object BeastTackleEngine {

    /** Inside this, the presser can go in hard. */
    const val BEAST_RADIUS_FRACTION_OF_HEIGHT = 0.12f
    /** Above this ball speed the ball is struck, not carried/shielded. */
    const val STRUCK_BALL_SPEED_PX_PER_S = 150f
    /** Approach dot above this = chasing from directly behind. */
    const val FROM_BEHIND_DOT = 0.5f
    /** Inside this of our goal centre, no beast tackle (penalty risk). */
    const val OWN_BOX_FRACTION_OF_WIDTH = 0.25f
    /** Carrier must be this far ahead of the tight line (px). */
    const val LINE_CLEARANCE_PX = 40f

    fun window(frame: RuntimeFrame): BeastWindow {
        val closed = { reason: String -> BeastWindow(false, reason, null) }
        if (!frame.trusted || frame.hasBall) return closed("no-ball-or-ours")
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return closed("no-capture")
        val ballSpeed = hypot(frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond)
        if (!ballSpeed.isFinite() || ballSpeed > STRUCK_BALL_SPEED_PX_PER_S) {
            return closed("ball-struck")
        }
        val teammates = frame.entities.filter { it.teammate }
        if (teammates.isEmpty()) return closed("no-teammates")
        val radius = BEAST_RADIUS_FRACTION_OF_HEIGHT * frame.captureHeight
        val presser = teammates.minByOrNull { t -> hypot(t.x - frame.ballX, t.y - frame.ballY) }
            ?: return closed("no-presser")
        val dist = hypot(presser.x - frame.ballX, presser.y - frame.ballY)
        if (dist > radius) return closed("out-of-range")
        // RISK GATE 1 — from behind.
        val ax = frame.ballX - presser.x
        val ay = frame.ballY - presser.y
        val aLen = hypot(ax, ay).coerceAtLeast(1f)
        val mLen = hypot(frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond)
        if (mLen > 1f) {
            val dot = (ax / aLen) * (frame.ballVelocityXPerSecond / mLen) +
                (ay / aLen) * (frame.ballVelocityYPerSecond / mLen)
            if (dot > FROM_BEHIND_DOT) return closed("from-behind")
        }
        // Our goal end (opposite the attack direction; detected box wins).
        val ownGoalX = if (frame.goalDetected) frame.goalCenterX
            else if (frame.attackDirection < 0) frame.captureWidth else 0f
        val ownGoalY = if (frame.goalDetected) frame.goalCenterY else frame.captureHeight * 0.5f
        // RISK GATE 2 — inside our own box (penalty risk).
        if (hypot(frame.ballX - ownGoalX, frame.ballY - ownGoalY) <=
            OWN_BOX_FRACTION_OF_WIDTH * frame.captureWidth
        ) return closed("own-box")
        val attackSign = if (frame.attackDirection < 0) -1f else 1f
        // RISK GATE 3 — last man: no teammate between carrier and goal.
        // Goal-side = toward our goal, i.e. opposite the attack
        // direction from the carrier.
        val hasCover = teammates.any { t -> (t.x - frame.ballX) * attackSign < 0f }
        if (!hasCover) return closed("last-man")
        // D14 GUARD — carrier ahead of the tight line toward the attack
        // end. Line unknown (defensiveLineX == 0 and not tight) means
        // the stance was never computed: fall back to middle-third —
        // the carrier must not be in our own third.
        if (frame.stanceTight && frame.defensiveLineX > 0f) {
            val ahead = (frame.ballX - frame.defensiveLineX) * attackSign
            if (ahead < LINE_CLEARANCE_PX) return closed("behind-line")
        } else {
            val ownThird = if (attackSign < 0) frame.ballX > frame.captureWidth * 2f / 3f
                else frame.ballX < frame.captureWidth / 3f
            if (ownThird) return closed("own-third-no-line")
        }
        return BeastWindow(true, "", presser.id)
    }
}
