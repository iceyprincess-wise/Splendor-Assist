package com.assistant.adapter.smartassist.contributors
import com.assistant.adapter.smartassist.ShotOpportunityAnalysisEngine
import com.assistant.runtime.*
object ShotOpportunityContributor : GameplayContributor {
    override val engineName = "ShotOpportunity"
    override val capabilities = setOf(EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val r = ShotOpportunityAnalysisEngine.analyze(
            (1650f - frame.ballX).coerceAtLeast(0f), frame.defenderDensity)
        if (r.openSideScore <= 0f) return null
        return EngineContribution(engineName, ActionClass.SHOT,
            frame.ballX, frame.ballY,
            (r.openSideScore / 10f).coerceIn(0f, 1f), r.confidence.coerceIn(0f, 1f), 35L)
    }
}
