package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object DashPressureContributor : GameplayContributor {
    override val engineName = "DashPressure"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        // Our defender position: use goalkeeper when detected, otherwise
        // project back from ball (defender tracks ball with lag).
        // When defX == oppX the engine geometry collapses to a zero vector.
        val defX = if (frame.goalkeeperVisible && frame.goalkeeperX > 0f)
            frame.goalkeeperX else (frame.ballX - 100f).coerceAtLeast(0f)
        val defY = if (frame.goalkeeperVisible && frame.goalkeeperY > 0f)
            frame.goalkeeperY else frame.ballY

        // Opponent ball carrier = ball position (they have the ball)
        val oppX = frame.ballX
        val oppY = frame.ballY

        // Queue item 6 (F7): the defender home is the LIVE home, not the
        // hardcoded (250, 360) — the same homeFound-guarded pattern
        // DefenseContributor uses. The guard is mandatory: the world
        // defender home defaults to no-line with x=0,y=0, so a raw
        // frame.homeX would regress to (0,0) when no line is found.
        val screenW = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val screenH = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        val packed = OmnipotentDashPressureMatrix.computeHighAuthorityDefensiveVector(
            ballX  = frame.ballX,
            ballY  = frame.ballY,
            defX   = defX,
            defY   = defY,
            defHomeX = if (frame.homeFound) frame.homeX else screenW * 0.5f,
            defHomeY = if (frame.homeFound) frame.homeY else screenH * 0.5f,
            oppX   = oppX,
            oppY   = oppY,
            isPlayerHoldingPressure = UserTouchState.isTouching(),
            // No68-I-D35: the matrix's emergency offer used to start from the
            // hardcoded joystick defaults (250, 550) while the contributor
            // path starts from the trained slot centre — the same press
            // swiped from two different origins. Pass the trained centre,
            // derived exactly as RuntimeDecisionLoop.chooseRequest does.
            joystickX = com.assistant.vision.CameraProfile.captureWidthOrFallback() * ControlMappingTrainer.joystickCenterX(),
            joystickY = com.assistant.vision.CameraProfile.captureHeightOrFallback() * ControlMappingTrainer.joystickCenterY(),
            // No68-H-D22: single dispatch — the matrix must not also offer
            // an emergency request; this contribution IS the dispatch path.
            offerEmergency = false,
            // Queue item 9 (L3): the line-broken signal — the press
            // targets the line home while the line is stretched.
            dragOutHeld = frame.dragOutHeld
        )

        val tx = OmnipotentDashPressureMatrix.unpackX(packed)
        val ty = OmnipotentDashPressureMatrix.unpackY(packed)
        if (!tx.isFinite() || !ty.isFinite()) return null
        if (tx <= 0f && ty <= 0f) return null
        // No68-I-D32: the matrix already dispatched this press straight to
        // the bus (INTERCEPTION emergency lane) when it offered. A second
        // ConAI contribution for the same press would double-dispatch — and
        // this contribution never wins arbitration anyway (Defense ties it
        // and is registered earlier). Skip it; the ConAI path below is the
        // fallback for frames the matrix did not offer on.
        // No68-MERGE NOTE: this check is intentionally INERT in the merged
        // tree. H-D22 makes this compute() pass offerEmergency = false, so
        // it never offers and the flag is always false here (the compute()
        // above resets it first). The emergency lane lives on AGC's direct
        // call (ActiveGestureController.injectWinningVector, default
        // offerEmergency = true, threat-gated). The check is retained so
        // the I-D32 guard stays wired if the offer is ever re-enabled.
        if (OmnipotentDashPressureMatrix.lastDirectOfferAccepted) return null

        return EngineContribution(
            engine         = engineName,
            actionClass    = ActionClass.DEFEND,
            targetX        = tx.coerceAtLeast(0f),
            targetY        = ty.coerceAtLeast(0f),
            // G-B (batch 4): stamina scales the press — a tired team
            // presses softer (scale in [0.55, 1.0], never silent).
            authority      = (frame.defenderDensity.coerceIn(0f, 1f) *
                com.assistant.StaminaEngine.pressScale(frame)).coerceIn(0f, 1f),
            confidence     = frame.confidence,
            durationHintMs = 42L
        )
    }
}

/* ========
DefenseAuthorityContributor
======== */
