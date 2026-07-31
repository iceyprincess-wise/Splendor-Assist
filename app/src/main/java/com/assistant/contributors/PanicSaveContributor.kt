package com.assistant.contributors

import com.assistant.overlay.interceptor.*
import com.assistant.runtime.*

/* One-v-one panic response. Highest-urgency keeper claim, still veto-gated. */
object PanicSaveContributor : GameplayContributor {
    override val engineName = "PanicSave"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val decision = ThreatPriorityContributor.decisionOf(frame) ?: return null

        val action = try { OneVsOnePanicEngine.evaluate(decision) } catch (_: Throwable) { null }
            ?: return null
        if (action == PanicAction.HOLD) return null

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
            authority = ((decision.priority / 130f) * 0.95f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 26L
        )
    }
}
