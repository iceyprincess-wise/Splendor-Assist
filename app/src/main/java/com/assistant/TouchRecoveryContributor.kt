package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object TouchRecoveryContributor : GameplayContributor {
    override val engineName = "TouchRecovery"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val pressure = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val strength = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val result = TouchRecoveryEngine.recover(pressure, strength)

        // After recovering the touch, drive toward the open passing lane.
        // Returning to ballX/ballY (where the player already is) is a no-op.
        // No68-E2-D14: a viable lane's target is authoritative even on the
        // edge (x/y == 0f is a legal coordinate); the old `> 0f` test
        // rejected it. Only finiteness is checked now. The no-lane
        // fallback still nudges +60px in X (tuned step, kept as-is) and
        // now clamps Y to the capture height too (it was unclamped).
        val targetX = when {
            frame.viableLaneCount > 0 && frame.passTargetX.isFinite() -> frame.passTargetX
            else -> (frame.ballX + 60f).coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        }
        val targetY = when {
            frame.viableLaneCount > 0 && frame.passTargetY.isFinite() -> frame.passTargetY
            else -> frame.ballY.coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())
        }

        return EngineContribution(
            engine         = engineName,
            actionClass    = ActionClass.MOVE,
            targetX        = targetX,
            targetY        = targetY,
            authority      = (result.recoveryBoost / 10f).coerceIn(0f, 1f),
            confidence     = frame.confidence,
            durationHintMs = 35L
        )
    }
}

/* ========
TouchRecoveryContributor
======== */
