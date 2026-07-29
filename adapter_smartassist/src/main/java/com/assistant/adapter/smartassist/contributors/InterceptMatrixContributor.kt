package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.HybridOmnipotentMatrixEngine
import com.assistant.runtime.*

object InterceptMatrixContributor : GameplayContributor {
    override val engineName = "InterceptMatrix"
    override val capabilities = setOf(EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null

        val packed = HybridOmnipotentMatrixEngine.computeGodspeedInterceptVector(
            myPlayerX = frame.ballX,
            myPlayerY = frame.ballY,
            oppPlayerX = frame.ballX,
            oppPlayerY = frame.ballY,
            oppVx = 0f,
            oppVy = 0f,
            ballX = frame.ballX,
            ballY = frame.ballY,
            ballVx = 0f,
            ballVy = 0f,
            isOpponentExecutingSkill = false
        )

        val tx = HybridOmnipotentMatrixEngine.unpackX(packed)
        val ty = HybridOmnipotentMatrixEngine.unpackY(packed)
        if (!tx.isFinite() || !ty.isFinite()) return null
        if (tx <= 0f && ty <= 0f) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = tx.coerceAtLeast(0f),
            targetY = ty.coerceAtLeast(0f),
            authority = (frame.defenderDensity * 0.9f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 40L
        )
    }
}
