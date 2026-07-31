package com.assistant.contributors

import com.assistant.overlay.interceptor.*
import com.assistant.runtime.*

/* Keeper positional bias. Reads the bias by name so it stays valid regardless
   of how the KeeperBias enum evolves. */
object KeeperBiasContributor : GameplayContributor {
    override val engineName = "KeeperBias"
    override val capabilities = setOf(EngineCapability.KEEPER)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val decision = ThreatPriorityContributor.decisionOf(frame) ?: return null

        val bias = try { KeeperPositionBiasEngine.evaluate(decision) } catch (_: Throwable) { null }
            ?: return null

        val name = bias.name.uppercase()
        val offsetY = when {
            name.contains("NEAR") -> -55f
            name.contains("FAR")  ->  55f
            name.contains("LEFT") -> -40f
            name.contains("RIGHT")->  40f
            else -> 0f
        }
        if (offsetY == 0f && !name.contains("CENTER")) return null

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = frame.ballX.coerceAtLeast(0f),
            targetY = (frame.ballY + offsetY).coerceAtLeast(0f),
            authority = (decision.priority / 150f).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 28L
        )
    }
}
