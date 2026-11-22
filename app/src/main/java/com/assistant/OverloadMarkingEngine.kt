package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-O — Overload marking (2v1) engine (engine-ideas §7, Ed D14).
 *
 * Ed: two players marking one player aggressively, no space — but only
 * one player was controlled. This engine is the PLAN half of that: it
 * names the top threat and picks its TWO markers (nearest teammate =
 * primary, second-nearest = secondary). The plan is published on the
 * frame (FrameAssembler: overloadOpponentId / overloadSecondaryId, and
 * the secondary assignment inside markingAssignments) so it is
 * observable in the frame snapshot and the log.
 *
 * BLOCKER (reported, not worked around): the app controls ONE player.
 * There is no second-player control mechanism anywhere in the repo —
 * RuntimeDecisionLoop arbitrates ONE winning contribution into ONE
 * ExecutionRequest, and ActiveGestureController drives ONE joystick.
 * The secondary marker therefore CANNOT be driven by this batch, and
 * this file does not invent a control channel: inventing one would be
 * a second joystick / second input stream the game may read as a
 * second touch, which is exactly the ghost-touch shape batch G fixed.
 * MarkingContributor drives the PRIMARY marker only. The secondary
 * assignment is published + logged so the missing half is visible and
 * a future second-player control decision (Ed's call) has a plan to
 * drive. Until then G-O is plan-only by construction, and the handoff
 * says so.
 */
data class OverloadPlan(
    /** Top-threat opponent id, or null when no overload is possible. */
    val opponentId: Int?,
    val primaryDefenderId: Int?,
    val secondaryDefenderId: Int?,
    /** Tight mark point (on the opponent, goal side). */
    val targetX: Float,
    val targetY: Float
)

object OverloadMarkingEngine {

    /** The overload target must be this close to our goal to be worth 2 men. */
    const val THREAT_RADIUS_FRACTION_OF_WIDTH = 0.40f
    /** Both markers must be within this of the threat to close the space. */
    const val MARKER_RADIUS_FRACTION_OF_HEIGHT = 0.35f

    fun plan(frame: RuntimeFrame): OverloadPlan {
        val none = OverloadPlan(null, null, null, 0f, 0f)
        if (!frame.trusted) return none
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return none
        val teammates = frame.entities.filter { it.teammate }
        val opponents = frame.entities.filter { !it.teammate }
        if (teammates.size < 2 || opponents.isEmpty()) return none
        val goalCx = if (frame.goalDetected) frame.goalCenterX else
            if (frame.attackDirection < 0) frame.captureWidth else 0f
        val goalCy = if (frame.goalDetected) frame.goalCenterY else frame.captureHeight * 0.5f
        val threatRadius = THREAT_RADIUS_FRACTION_OF_WIDTH * frame.captureWidth
        // Top threat: nearest our goal, inside the threat radius.
        val threat = opponents
            .filter { e -> hypot(e.x - goalCx, e.y - goalCy) <= threatRadius }
            .minByOrNull { e -> hypot(e.x - goalCx, e.y - goalCy) } ?: return none
        val markerRadius = MARKER_RADIUS_FRACTION_OF_HEIGHT * frame.captureHeight
        val near = teammates
            .filter { t -> hypot(t.x - threat.x, t.y - threat.y) <= markerRadius }
            .sortedBy { t -> hypot(t.x - threat.x, t.y - threat.y) }
        if (near.size < 2) return none
        val primary = near[0]
        val secondary = near[1]
        val assignment = MarkingEngine.assignmentFor(primary.id, threat, goalCx, goalCy, frame)
        return OverloadPlan(
            opponentId = threat.id,
            primaryDefenderId = primary.id,
            secondaryDefenderId = secondary.id,
            targetX = assignment.targetX,
            targetY = assignment.targetY
        )
    }
}
