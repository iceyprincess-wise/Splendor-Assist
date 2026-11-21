package com.assistant

import com.assistant.runtime.MarkingAssignment
import com.assistant.runtime.RuntimeEntity
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-F — Marking Engine (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G6): defenders were never assigned to
 * mark specific opponents. Man/zonal mark: 0 files.
 *
 * Assignments are greedy by threat: opponents are ranked by distance
 * to OUR goal (nearest first — the most dangerous), then by distance
 * to the ball; each is paired with the nearest still-free teammate.
 * The mark target is tight to the opponent (the mark stands on the
 * goal side of the man, between him and our goal, so the mark is a
 * goal-side position, not the opponent's exact pixel).
 *
 * The assignments are published on the frame (FrameAssembler) so the
 * defensive-line contributors and the LowBlock engine can read the same
 * plan, and MarkingContributor drives the controlled defender to his
 * assignment. D14 guard: marking never drags the line — the assignment
 * target is clamped to stay on the goal side of the opponent and never
 * beyond the tight defensive line toward the attack end; the line
 * itself (defensiveLineX) is untouched by this engine.
 *
 * MarkingAssignment lives in com.assistant.runtime (RuntimeFrame.kt) —
 * the frame carries it, so the engine uses the same type, not a copy.
 */
object MarkingEngine {

    fun assign(frame: RuntimeFrame): List<MarkingAssignment> {
        if (!frame.trusted) return emptyList()
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return emptyList()
        val teammates = frame.entities.filter { it.teammate }
        val opponents = frame.entities.filter { !it.teammate }
        if (teammates.isEmpty() || opponents.isEmpty()) return emptyList()
        val goalCx = if (frame.goalDetected) frame.goalCenterX else
            if (frame.attackDirection < 0) frame.captureWidth else 0f
        val goalCy = if (frame.goalDetected) frame.goalCenterY else frame.captureHeight * 0.5f
        // Threat order: nearest our goal first, then nearest the ball.
        val ranked = opponents.sortedWith(
            compareBy(
                { e -> hypot(e.x - goalCx, e.y - goalCy) },
                { e -> hypot(e.x - frame.ballX, e.y - frame.ballY) }
            )
        )
        val free = teammates.toMutableList()
        val out = mutableListOf<MarkingAssignment>()
        for (opp in ranked) {
            val marker = free.minByOrNull { t -> hypot(t.x - opp.x, t.y - opp.y) } ?: break
            free.remove(marker)
            out.add(assignmentFor(marker.id, opp, goalCx, goalCy, frame))
        }
        return out
    }

    /** Goal-side mark point for one defender/opponent pair. */
    fun assignmentFor(
        defenderId: Int,
        opponent: RuntimeEntity,
        goalCx: Float,
        goalCy: Float,
        frame: RuntimeFrame,
        overloadSecondary: Boolean = false
    ): MarkingAssignment {
        // Stand between the opponent and our goal, half a step off him:
        // 12% of the opponent-to-goal distance toward the goal.
        val dx = goalCx - opponent.x
        val dy = goalCy - opponent.y
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val step = (dist * 0.12f).coerceAtMost(40f)
        // Queue item 9 (L1b): the D14 guard this header always claimed
        // but assignmentFor never enforced — before this round the target
        // was clamped only to screen bounds, so a mark could drag its
        // man off the tight line (a drag-out SOURCE). The target must
        // never cross the tight line toward the attack end: the line is
        // the stance band [lineX ± spread/2] (frame.defensiveLineX /
        // defensiveLineSpread), the goal side is goalCx. Guarded on
        // stanceTight: without a measured stance there is no line to
        // clamp to, and the screen clamp below still applies.
        val rawTx = opponent.x + dx / dist * step
        val tx = if (frame.stanceTight) {
            val halfSpread = frame.defensiveLineSpread * 0.5f
            if (goalCx <= frame.defensiveLineX) {
                rawTx.coerceAtMost(frame.defensiveLineX + halfSpread)
            } else {
                rawTx.coerceAtLeast(frame.defensiveLineX - halfSpread)
            }
        } else rawTx
        val txClamped = tx.coerceIn(0f, frame.captureWidth)
        val ty = (opponent.y + dy / dist * step).coerceIn(0f, frame.captureHeight)
        return MarkingAssignment(defenderId, opponent.id, txClamped, ty, overloadSecondary)
    }
}
