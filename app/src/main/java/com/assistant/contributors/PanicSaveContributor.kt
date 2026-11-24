package com.assistant.contributors
import com.assistant.NativePorts
import com.assistant.SmartAssistRepository


import com.assistant.overlay.interceptor.*
import com.assistant.runtime.*

object PanicSaveContributor : GameplayContributor {
    override val engineName = "PanicSave"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // PANIC GATE: the KEEPER panic-save claim only fires when the user
        // enabled Panic. Same flag the OCR auto-panic gate reads
        // (OverlayService.kt:1512, SmartAssistRepository.panicUserEnabled).
        // Without this, panic saves emit through ConAI even with the Panic
        // switch OFF. Also skips the decisionOf computation when off.
        if (!SmartAssistRepository.panicUserEnabled()) return null
        if (frame.confidence < 0.05f) return null
        val possessionWeight = if (!frame.hasBall) 1.0f else 0.2f
        // RF-D9 (batch 5): the trustWeight fallback (0.7f on untrusted) was
        // dead — GameplayEngineRegistry.collect() returns empty before
        // contributors on an untrusted frame, so frame.trusted is always
        // true here (same as ShotContributor's No68-F-D14 fix).
        val fluidMultiplier = possessionWeight
        val decision = ThreatPriorityContributor.decisionOf(frame) ?: return null

        val outBuffer = FloatArray(1)
        NativePorts.panic(
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
        } catch (_: Throwable) { false }
        if (!safeToExecute) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = ((decision.priority / 150f) * fluidMultiplier).coerceIn(0f, 1f), // SPLENDOR_P12_T4_PANIC: the unexplained 0.95 haircut on the highest-urgency keeper action is removed.
            confidence = frame.confidence,
            durationHintMs = 26L
        )
    }
}
