package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-E + G-P — Dribbling / Skill-Move Engine (engine-ideas §4 + §7).
 *
 * Gap (absence PROVEN, repo map G4): the ball carrier had no dribble /
 * skill option — only pass/shoot/cross. "dribbl": 6 files, comments
 * only; "skill move": 1 file, ControlMappingTrainer text only.
 *
 * Ed D11 (decided): he does not perform skills/dribbles himself (he
 * would get intercepted) — if added, it must be AUTOPILOT while holding
 * the dash and pressure buttons. That is the G-P contract, and this
 * engine serves both items:
 *  - DRIBBLE (G-E): open space ahead + no defender inside the tackle
 *    radius — a plain autopilot dribble into space. Fires without the
 *    dash hold: space is the safety.
 *  - SKILL_MOVE (G-P): a defender IS inside the tackle radius — the
 *    autopilot only attempts the skill while the user is holding dash +
 *    pressure (dashHeld). Without the hold, the engine stays silent and
 *    the carrier keeps the safe options (pass/shoot) — a failed dribble
 *    loses the ball, so the risky path is never taken hands-off.
 *
 * "Holding dash + pressure" is read from the frame (frame.isDashing)
 * — the ring reader's colour state (CYAN = dashing/in possession,
 * PALE = not), a dash/possession PROXY: the footage evidence is
 * possession-based and Ed's is Dash-based, and the two triggers are
 * not yet separated on-device (#45). It replaces
 * ControlMappingTrainer's dash-hold candidate, which had ZERO live
 * evidence (0 DASH_HOLD_CANDIDATE lines in either logged session)
 * and never fired (0 SKILL_MOVE lines in both sessions). The engine
 * is pure over the Boolean — the contributor passes it in as
 * dashHeld, and the engine never observes the hold itself.
 *
 * The output is a MOVE-class vector (joystick drag toward open space),
 * so it rides the existing MOVE path: the batch-G touch gate (user must
 * be touching — true while holding dash) and the B10 load-shed MOVE
 * gate both still apply. No new dispatch channel.
 */
enum class DribbleKind { NONE, DRIBBLE, SKILL_MOVE }

data class DribbleDecision(
    val kind: DribbleKind,
    val targetX: Float,
    val targetY: Float,
    /** Distance to the nearest opponent along the chosen line. */
    val spaceAhead: Float
)

object DribbleEngine {

    /** Inside this, a defender can tackle the carrier. */
    const val TACKLE_RADIUS_FRACTION_OF_HEIGHT = 0.08f
    /** Dribble step length, as a fraction of the smaller capture side. */
    const val DRIBBLE_STEP_FRACTION = 0.06f

    fun decide(frame: RuntimeFrame, dashHeld: Boolean): DribbleDecision {
        val none = DribbleDecision(DribbleKind.NONE, 0f, 0f, 0f)
        if (!frame.trusted || !frame.hasBall) return none
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return none
        val opponents = frame.entities.filter { !it.teammate }
        if (opponents.isEmpty()) return none
        val tackleRadius = TACKLE_RADIUS_FRACTION_OF_HEIGHT * frame.captureHeight
        val nearest = opponents.minByOrNull { e ->
            hypot(e.x - frame.ballX, e.y - frame.ballY)
        } ?: return none
        val nearestDist = hypot(nearest.x - frame.ballX, nearest.y - frame.ballY)
        // A defender inside the tackle radius is a skill-move frame:
        // only with the dash hold (G-P). Without it, silence — the
        // carrier must not dribble into a tackle hands-off.
        if (nearestDist <= tackleRadius && !dashHeld) return none

        // Pick the most open direction: attack direction, plus/minus
        // 30 degrees. Score = distance to the nearest opponent in that
        // direction + progress toward the attack end.
        val attackSign = if (frame.attackDirection < 0) -1f else 1f
        val step = DRIBBLE_STEP_FRACTION * minOf(frame.captureWidth, frame.captureHeight)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestTargetX = frame.ballX
        var bestTargetY = frame.ballY
        for (deg in -30..30 step 15) {
            val rad = Math.toRadians(deg.toDouble()).toFloat()
            val dx = attackSign * kotlin.math.cos(rad)
            val dy = kotlin.math.sin(rad)
            val tx = frame.ballX + dx * step
            val ty = frame.ballY + dy * step
            val nearestAlong = opponents.minOf { e -> hypot(e.x - tx, e.y - ty) }
            val progress = (tx - frame.ballX) * attackSign
            val score = nearestAlong + progress * 0.5f
            if (score > bestScore) {
                bestScore = score
                bestTargetX = tx
                bestTargetY = ty
            }
        }
        val spaceAhead = opponents.minOf { e -> hypot(e.x - bestTargetX, e.y - bestTargetY) }
        val kind = if (nearestDist <= tackleRadius) DribbleKind.SKILL_MOVE else DribbleKind.DRIBBLE
        return DribbleDecision(kind, bestTargetX, bestTargetY, spaceAhead)
    }
}
