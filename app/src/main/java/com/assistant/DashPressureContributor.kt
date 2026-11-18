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

        val packed = OmnipotentDashPressureMatrix.computeHighAuthorityDefensiveVector(
            ballX  = frame.ballX,
            ballY  = frame.ballY,
            defX   = defX,
            defY   = defY,
            defHomeX = 250f,
            defHomeY = 360f,
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
            offerEmergency = false
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
            authority      = frame.defenderDensity.coerceIn(0f, 1f),
            confidence     = frame.confidence,
            durationHintMs = 42L
        )
    }
}

/* ========
DefenseAuthorityContributor
======== */
