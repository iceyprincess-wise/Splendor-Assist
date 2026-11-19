package com.assistant.contributors

import com.assistant.runtime.*

/*
 * G-F (+ G-O primary) — Marking contributor.
 *
 * Drives the controlled defender to his marking assignment
 * (MarkingEngine, published on the frame by FrameAssembler). The
 * controlled defender is frame.defenderId when the world store names
 * one, else the teammate nearest the ball. The mark target is the
 * goal-side point next to the assigned opponent — the defender stands
 * between his man and our goal.
 *
 * G-O note: this contributor drives the PRIMARY marker only. The
 * overload secondary assignment is published on the frame
 * (overloadSecondaryId) but has no driver — there is no second-player
 * control mechanism in the repo (see OverloadMarkingEngine), and none
 * is invented here. When this contributor's defender IS the overload
 * primary, the offer is the same mark — the 2v1 collapses to a 1v1
 * with the second man planned but undriven, and the handoff says so.
 */
object MarkingContributor : GameplayContributor {
    override val engineName = "Marking"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && !frame.hasBall && frame.markingAssignments.isNotEmpty()

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.markingAssignments.isEmpty()) return null
        val controlledId = frame.defenderId ?: frame.entities
            .filter { it.teammate }
            .minByOrNull { e ->
                (e.x - frame.ballX) * (e.x - frame.ballX) +
                    (e.y - frame.ballY) * (e.y - frame.ballY)
            }?.id
        val assignment = frame.markingAssignments.firstOrNull { it.defenderId == controlledId }
            ?: return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = assignment.targetX.coerceIn(0f, frame.captureWidth),
            targetY = assignment.targetY.coerceIn(0f, frame.captureHeight),
            authority = 0.55f,
            confidence = frame.confidence,
            durationHintMs = 40L,
            targetEntityId = assignment.opponentId
        )
    }
}
