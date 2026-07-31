package com.assistant.adapter.smartassist.contributors
import com.assistant.adapter.smartassist.ForwardRunOpportunityEngine
import com.assistant.runtime.*
import kotlin.math.hypot
object ForwardRunContributor : GameplayContributor {
    override val engineName = "ForwardRun"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val d = hypot(frame.passTargetX - frame.ballX, frame.passTargetY - frame.ballY)
        val s = (frame.bestLaneConfidence * 100f).toInt().coerceIn(0, 100)
        val r = ForwardRunOpportunityEngine.evaluate(d, s)
        return EngineContribution(engineName, ActionClass.MOVE,
            frame.ballX, frame.ballY,
            (r.runBoost / 10f).coerceIn(0f, 1f), r.confidence.coerceIn(0f, 1f), 38L)
    }
}
