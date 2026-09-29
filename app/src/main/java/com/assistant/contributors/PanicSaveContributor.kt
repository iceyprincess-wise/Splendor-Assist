package com.assistant.contributors

import com.assistant.NativeBridge
import com.assistant.overlay.interceptor.*
import com.assistant.runtime.*

object PanicSaveContributor : GameplayContributor {
    override val engineName = "PanicSave"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.DEFENSE)

    private val outBuffer = FloatArray(1)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (frame.confidence < 0.05f) return null
        val possessionWeight = if (!frame.hasBall) 1.0f else 0.2f
        val trustWeight = if (frame.trusted) 1.0f else 0.7f
        val fluidMultiplier = possessionWeight * trustWeight
        val decision = ThreatPriorityContributor.decisionOf(frame) ?: return null

        NativeBridge.nativePanicSaveCompute(
            decision.direction.ordinal,
            decision.priority,
            outBuffer
        )

        val actionOrdinal = outBuffer[0].toInt()
        if (actionOrdinal == 0) return null // HOLD

        val safeToExecute = try {
            OwnGoalAvoidanceEngine.allowExecution(
                OwnGoalAvoidanceEngine.evaluate(decision.direction, decision.zone)
            )
        } catch (_: Throwable) { true }
        if (!safeToExecute) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = (((decision.priority / 130f) * 0.95f) * fluidMultiplier).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 26L
        )
    }
}
