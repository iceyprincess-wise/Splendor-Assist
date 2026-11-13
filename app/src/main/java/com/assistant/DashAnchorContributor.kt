package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object DashAnchorContributor : GameplayContributor {
    override val engineName = "DashAnchor"
    override val capabilities = setOf(EngineCapability.MOVEMENT)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        // Resolve high-speed target vector with fallback guarantees
        val directionalX = when {
            frame.passTargetX > 0f -> frame.passTargetX
            frame.goalDetected && frame.goalRightX > 0f ->
                (frame.goalRightX + frame.goalLeftX) * 0.5f
            else -> frame.ballX + 200f
        }
        val directionalY = when {
            frame.passTargetY > 0f -> frame.passTargetY
            frame.goalDetected && frame.goalTopY > 0f ->
                (frame.goalTopY + frame.goalBottomY) * 0.5f
            else -> frame.ballY
        }

        val result = MagneticDashAnchor.computeAnchorTarget(
            dashX = frame.ballX,
            dashY = frame.ballY,
            directionalX = directionalX,
            directionalY = directionalY
        )

        // SPLENDOR_P10_DASH_STILL: the stateless anchor's strength
        // has a 0.35 floor (0.5 when dist == 0), so it used to emit a
        // MOVE on every possession frame even with a stationary ball.
        // Suppress the contribution when neither the ball nor the
        // directional target is moving (both under 1 px/frame).
        val dashBallSpeed = hypot(frame.ballVelocityX.toDouble(), frame.ballVelocityY.toDouble()).toFloat()
        if (dashBallSpeed < 1f && result.dragVelocity < 1f) return null

        if (result.strength <= 0f) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = result.anchorX,
            targetY = result.anchorY,
            authority = result.strength.coerceIn(0f, 1f),
            confidence = frame.confidence,
            // SPLENDOR_P10_DASH_DUR: ultra-fast drag (> 18 px/frame,
            // the MagneticDashAnchor instance duration branch) gets the
            // 10 ms hint; turning 25 ms; else 40 ms.
            durationHintMs = when {
                result.dragVelocity > 18.0f -> 10L
                result.turning -> 25L
                else -> 40L
            }
        )
    }
}

/* ========
DashPressureContributor
======== */
