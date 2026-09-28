package com.assistant.contributors

import com.assistant.NativeBridge
import com.assistant.overlay.interceptor.*
import com.assistant.runtime.*

object KeeperBiasContributor : GameplayContributor {
    override val engineName = "KeeperBias"
    override val capabilities = setOf(EngineCapability.KEEPER)

    private val outBuffer = FloatArray(4)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val decision = ThreatPriorityContributor.decisionOf(frame) ?: return null

        NativeBridge.nativeKeeperBiasCompute(
            decision.zone.ordinal,
            decision.direction.ordinal,
            decision.priority,
            frame.ballX.coerceAtLeast(0f),
            frame.ballY.coerceAtLeast(0f),
            outBuffer
        )

        if (outBuffer[0] < 0.5f) return null // Invalid gate

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = outBuffer[1],
            targetY = outBuffer[2],
            authority = outBuffer[3],
            confidence = frame.confidence,
            durationHintMs = 28L
        )
    }
}
