package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.TrueTargetPassingEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object PassingContributor : GameplayContributor {
    override val engineName = "Passing"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall || frame.viableLaneCount <= 0) return null
        val graph = try { TrueTargetPassingEngine.currentPassingGraph() } catch (_: Throwable) { null }
        val lane = graph?.lanes?.firstOrNull { !it.blocked } ?: return null
        val rx = lane.receiver?.x ?: return null
        val ry = lane.receiver?.y ?: return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = rx,
            targetY = ry,
            authority = frame.bestLaneConfidence.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
