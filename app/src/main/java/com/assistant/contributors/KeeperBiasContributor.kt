package com.assistant.contributors
import com.assistant.NativePorts


import com.assistant.overlay.interceptor.*
import com.assistant.runtime.*

object KeeperBiasContributor : GameplayContributor {
    override val engineName = "KeeperBias"
    override val capabilities = setOf(EngineCapability.KEEPER)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val decision = ThreatPriorityContributor.decisionOf(frame) ?: return null

        val outBuffer = FloatArray(4)
        NativePorts.keeper(
            decision.zone.ordinal,
            decision.direction.ordinal,
            decision.priority,
            frame.ballX.coerceAtLeast(0f),
            frame.ballY.coerceAtLeast(0f),
            outBuffer
        )

        if (outBuffer[0] < 0.5f) return null // Invalid gate

        val w = if (frame.captureWidth > 0f) frame.captureWidth else com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val h = if (frame.captureHeight > 0f) frame.captureHeight else com.assistant.vision.CameraProfile.captureHeightOrFallback()

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = outBuffer[1].coerceIn(0f, w),
            targetY = outBuffer[2].coerceIn(0f, h),
            authority = outBuffer[3].coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 28L
        )
    }
}
