package com.assistant.adapter.smartassist.contributors
import com.assistant.adapter.smartassist.DefenseAuthorityEngine
import com.assistant.runtime.*
object DefenseAuthorityContributor : GameplayContributor {
    override val engineName = "DefenseAuthority"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        val s = (frame.defenderDensity * 100f).toInt().coerceIn(0, 100)
        val r = DefenseAuthorityEngine.evaluate(
            (1f - frame.defenderDensity) * 500f, s,
            frame.confidence * 10f, frame.bestLaneConfidence * 10f)
        return EngineContribution(engineName, ActionClass.DEFEND,
            frame.ballX, frame.ballY,
            ((r.containment + r.interception) / 2f).coerceIn(0f, 1f),
            frame.confidence, 32L)
    }
}
