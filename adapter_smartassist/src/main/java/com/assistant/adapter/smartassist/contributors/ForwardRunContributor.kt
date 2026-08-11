package com.assistant.adapter.smartassist.contributors
import com.assistant.adapter.smartassist.ForwardRunOpportunityEngine
import com.assistant.runtime.*
import kotlin.math.hypot
object ForwardRunContributor : GameplayContributor {
    override val engineName = "ForwardRun"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        // Without a viable lane, passTargetX/Y = 0 → hypot(0-ballX, 0-ballY) is
        // distance to screen corner — a corrupted input to the engine.
        if (frame.viableLaneCount <= 0) return null
        val d = hypot(frame.passTargetX - frame.ballX, frame.passTargetY - frame.ballY)
        val s = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val r = ForwardRunOpportunityEngine.evaluate(d, s)
        // Run TOWARD the open lane, not back to ballX/ballY (zero-movement).
        val targetX = frame.passTargetX.coerceAtLeast(0f)
        val targetY = frame.passTargetY.coerceAtLeast(0f)
        return EngineContribution(engineName, ActionClass.MOVE,
            targetX, targetY,
            (r.runBoost / 10f).coerceIn(0f, 1f), r.confidence.coerceIn(0f, 1f), 38L)
    }
}
