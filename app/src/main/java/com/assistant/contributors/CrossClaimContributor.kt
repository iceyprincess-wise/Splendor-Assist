package com.assistant.contributors

import com.assistant.overlay.interceptor.*
import com.assistant.runtime.*

/* Cross-claim keeper action. Contributes only when the engine asks for
   something other than HOLD, and only when collision risk permits a claim. */
object CrossClaimContributor : GameplayContributor {
    override val engineName = "CrossClaim"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val decision = ThreatPriorityContributor.decisionOf(frame) ?: return null

        val action = try { CrossClaimEngine.evaluate(decision) } catch (_: Throwable) { null }
            ?: return null
        if (action == CrossAction.HOLD) return null

        val claimAllowed = try {
            CollisionAvoidanceEngine.allowClaim(
                CollisionAvoidanceEngine.evaluate(decision)
            )
        } catch (_: Throwable) { true }
        if (!claimAllowed) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = (decision.priority / 140f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 32L
        )
    }
}
