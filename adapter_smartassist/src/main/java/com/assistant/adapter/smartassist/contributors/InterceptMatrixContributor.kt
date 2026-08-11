package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.HybridOmnipotentMatrixEngine
import com.assistant.runtime.*

object InterceptMatrixContributor : GameplayContributor {
    override val engineName = "InterceptMatrix"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        // My defending player: goalkeeper position when detected, otherwise
        // estimate from ball (defender trails ball slightly)
        val myX = if (frame.goalkeeperVisible && frame.goalkeeperX > 0f)
            frame.goalkeeperX else (frame.ballX - 80f).coerceAtLeast(0f)
        val myY = if (frame.goalkeeperVisible && frame.goalkeeperY > 0f)
            frame.goalkeeperY else frame.ballY

        // Opponent carrier: where the passing engine projects the ball going
        val oppX = if (frame.passTargetX > 0f) frame.passTargetX
                   else (frame.ballX + 120f).coerceIn(0f, 1920f)
        val oppY = if (frame.passTargetY > 0f) frame.passTargetY else frame.ballY

        // Direction hint for opponent velocity (magnitude kept small)
        val ovx = (oppX - frame.ballX) * 0.08f
        val ovy = (oppY - frame.ballY) * 0.08f

        val packed = HybridOmnipotentMatrixEngine.computeGodspeedInterceptVector(
            myPlayerX            = myX,
            myPlayerY            = myY,
            oppPlayerX           = oppX,
            oppPlayerY           = oppY,
            oppVx                = ovx,
            oppVy                = ovy,
            ballX                = frame.ballX,
            ballY                = frame.ballY,
            ballVx               = 0f,
            ballVy               = 0f,
            isOpponentExecutingSkill = false
        )

        val tx = HybridOmnipotentMatrixEngine.unpackX(packed)
        val ty = HybridOmnipotentMatrixEngine.unpackY(packed)
        if (!tx.isFinite() || !ty.isFinite()) return null
        if (tx <= 0f && ty <= 0f) return null

        return EngineContribution(
            engine          = engineName,
            actionClass     = ActionClass.DEFEND,
            targetX         = tx.coerceAtLeast(0f),
            targetY         = ty.coerceAtLeast(0f),
            authority       = (frame.defenderDensity * 0.9f).coerceIn(0f, 1f),
            confidence      = frame.confidence,
            durationHintMs  = 40L
        )
    }
}
