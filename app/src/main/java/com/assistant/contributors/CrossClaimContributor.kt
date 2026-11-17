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
            // No64-D29 (F8): allow LOW or MEDIUM collision for claims.
            // CollisionAvoidanceEngine.allowClaim itself is unchanged
            // (the router's CLAIM gate still uses LOW-only); CROSS at
            // default sliders is always >= MEDIUM, so LOW-only made this
            // contributor provably dead. HIGH still vetoes.
            val risk = CollisionAvoidanceEngine.evaluate(decision)
            risk == CollisionRisk.LOW || risk == CollisionRisk.MEDIUM
        } catch (_: Throwable) { false }
        if (!claimAllowed) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = frame.ballY.coerceAtLeast(0f),
            authority = (decision.priority / 150f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 32L
        )
    }
}
