package com.assistant.contributors

import com.assistant.DribbleEngine
import com.assistant.DribbleKind
import com.assistant.runtime.*

/*
 * G-E + G-P — Dribble contributor (autopilot dribble / skill moves).
 *
 * DribbleEngine.decide returns DRIBBLE (open space, no defender in
 * tackle range — safe without any hold) or SKILL_MOVE (defender in
 * range — ONLY while the user holds dash + pressure, D11/G-P). The
 * hold is now frame.isDashing — the ring reader's colour state
 * (CYAN = dashing/in possession, PALE = not), a dash/possession
 * PROXY: the footage evidence is possession-based and Ed's is
 * Dash-based, and the two triggers are not yet separated on-device.
 * It replaces ControlMappingTrainer's dash-hold candidate, which had
 * ZERO live evidence (0 DASH_HOLD_CANDIDATE lines in either logged
 * session) and never fired (0 SKILL_MOVE lines in both sessions).
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

    // Item 17 (Letter D, D2-F02): DribbleDecision.spaceAhead had no
    // reader — the contributor read kind/targetX/targetY only. The
    // field's purpose is the safety margin of the offered dribble, so
    // the contributor now logs it on the kind transition that opens a
    // dribble/skill offer (one line per transition, never per frame).
    // Same pattern as BodyWeightPressContributor's lean-break log
    // (Letter B, item 15). No gate or authority change: the value is
    // diagnostic only; the engine's own pick already maximises it.
    @Volatile private var lastKind = DribbleKind.NONE

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val dashHeld = frame.isDashing
        val decision = DribbleEngine.decide(frame, dashHeld)
        if (decision.kind == DribbleKind.NONE) {
            lastKind = DribbleKind.NONE
            return null
        }
        // D2-F02: kind-change read of spaceAhead.
        if (decision.kind != lastKind) {
            try {
                com.assistant.diagnostic.RuntimeLogger.log(
                    "DRIBBLE ${decision.kind} spaceAhead=${decision.spaceAhead} target=(${decision.targetX},${decision.targetY})",
                    "DRIBBLE"
                )
            } catch (_: Throwable) { }
        }
        lastKind = decision.kind
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

    override fun reset() {
        lastKind = DribbleKind.NONE
    }
}
