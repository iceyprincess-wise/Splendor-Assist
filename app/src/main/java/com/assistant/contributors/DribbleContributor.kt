package com.assistant.contributors

import com.assistant.ControlMappingTrainer
import com.assistant.DribbleEngine
import com.assistant.DribbleKind
import com.assistant.runtime.*

/*
 * G-E + G-P — Dribble contributor (autopilot dribble / skill moves).
 *
 * DribbleEngine.decide returns DRIBBLE (open space, no defender in
 * tackle range — safe without any hold) or SKILL_MOVE (defender in
 * range — ONLY while the user holds dash + pressure, D11/G-P). The
 * hold is ControlMappingTrainer's dash-hold candidate (slot-4 pixel
 * occlusion + touch, inferred like the D13 through press — never a
 * confirmed hardware press).
 *
 * MOVE-class: a joystick drag toward open space. It rides the existing
 * MOVE path, so the batch-G touch gate (user must be touching — true
 * while holding dash) and the B10 load-shed MOVE gate both still
 * apply. Authority is modest (0.45 dribble / 0.55 skill) — a dribble
 * must never outrank a clean pass or shot on the same frame; it is
 * the option when space is open, not the default.
 */
object DribbleContributor : GameplayContributor {
    override val engineName = "Dribble"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.MOVEMENT)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val dashHeld = try {
            ControlMappingTrainer.dashHoldCandidateFresh()
        } catch (_: Throwable) { false }
        val decision = DribbleEngine.decide(frame, dashHeld)
        if (decision.kind == DribbleKind.NONE) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = decision.targetX.coerceIn(0f, frame.captureWidth),
            targetY = decision.targetY.coerceIn(0f, frame.captureHeight),
            authority = if (decision.kind == DribbleKind.SKILL_MOVE) 0.55f else 0.45f,
            confidence = frame.confidence,
            durationHintMs = if (decision.kind == DribbleKind.SKILL_MOVE) 30L else 40L
        )
    }
}
