package com.assistant.adapter.smartassist.contributors
import com.assistant.adapter.smartassist.TrueTargetPassingEngine
import com.assistant.runtime.*
object TruePassContributor : GameplayContributor {
    override val engineName = "TruePass"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall || frame.viableLaneCount <= 0) return null
        val r = TrueTargetPassingEngine.optimize(
            frame.ballX, frame.ballY, frame.passTargetX, frame.passTargetY,
            frame.bestLaneConfidence * 10f)
        return EngineContribution(engineName, ActionClass.PASS,
            r.correctedX.coerceAtLeast(0f), r.correctedY.coerceAtLeast(0f),
            ((1f - r.interceptionRisk) * frame.bestLaneConfidence).coerceIn(0f, 1f),
            frame.confidence, 45L)
    }
}
