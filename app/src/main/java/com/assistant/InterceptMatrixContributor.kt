package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object InterceptMatrixContributor : GameplayContributor {
    override val engineName = "InterceptMatrix"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        // No68-H-D28: "my defending player" was the GOALKEEPER's position
        // — the keeper is not the defender. Use the frame's defender
        // entity; fall back to trailing the ball only when it is absent.
        val defender = frame.entities.firstOrNull { it.id == frame.defenderId && it.teammate }
        val myX = defender?.x ?: (frame.ballX - 80f).coerceAtLeast(0f)
        val myY = defender?.y ?: frame.ballY

        // No68-H-D29: the "opponent carrier" was passTargetX/Y — where the
        // passing engine projects the ball GOING, not where the carrier IS
        // — and the velocity was a fabricated (opp-ball)*0.08 position
        // difference, not a velocity. Use the nearest opponent to the ball
        // (the carrier) and that entity's real per-second velocity; the
        // matrix normalises it in the close branch, so units cancel there.
        val carrier = frame.entities.filter { !it.teammate }.minByOrNull { e ->
            val dx = e.x - frame.ballX
            val dy = e.y - frame.ballY
            dx * dx + dy * dy
        }
        val oppX = carrier?.x ?: frame.ballX
        val oppY = carrier?.y ?: frame.ballY
        val ovx = carrier?.velocityXPerSecond ?: 0f
        val ovy = carrier?.velocityYPerSecond ?: 0f

        val packed = HybridOmnipotentMatrixEngine.computeGodspeedInterceptVector(
            myPlayerX            = myX,
            myPlayerY            = myY,
            oppPlayerX           = oppX,
            oppPlayerY           = oppY,
            oppVx                = ovx,
            oppVy                = ovy,
            ballX                = frame.ballX,
            ballY                = frame.ballY,
            // Queue item 11 (F1): the ball velocity is REAL now — the
            // frame's dt-measured per-second velocity
            // (PredictiveState.project). The matrix's lookAhead is a
            // seconds horizon (0.14 s / 0.06 s, converted from the old
            // 2.8f/1.2f frame counts at the 20 fps processed rate), so
            // wiring px/s no longer saturates the lead (QH-2 resolved).
            // isOpponentExecutingSkill stays false: no skill-move
            // detector exists anywhere in the repo (only this call and
            // the matrix mention skill) — the 0.06 s branch is
            // unreachable until one is built (item 13).
            ballVx               = frame.ballVelocityXPerSecond,
            ballVy               = frame.ballVelocityYPerSecond,
            isOpponentExecutingSkill = false,
            // No68-H-D15: single dispatch — the matrix must not also offer
            // an emergency request; this contribution IS the dispatch path.
            offerEmergency = false
        )

        val tx = HybridOmnipotentMatrixEngine.unpackX(packed)
        val ty = HybridOmnipotentMatrixEngine.unpackY(packed)
        if (!tx.isFinite() || !ty.isFinite()) return null
        // Targets are coerced to [0, screen] inside the matrix, so only the
        // exact (0,0) degenerate target is rejected here.
        if (tx == 0f && ty == 0f) return null

        return EngineContribution(
            engine          = engineName,
            actionClass     = ActionClass.DEFEND,
            targetX         = tx.coerceAtLeast(0f),
            targetY         = ty.coerceAtLeast(0f),
            // Authority floor 0.50 + 0.50*density is a deliberate tactical
            // number (strictly dominates Defense/DashPressure on every
            // frame it fires) — NOT silently re-tuned; see QH-1.
            authority       = (0.50f + frame.defenderDensity * 0.50f).coerceIn(0f, 1f),
            confidence      = frame.confidence,
            durationHintMs  = 40L
        )
    }
}
