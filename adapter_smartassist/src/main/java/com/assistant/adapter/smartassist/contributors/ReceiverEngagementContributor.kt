package com.assistant.adapter.smartassist.contributors
import com.assistant.adapter.smartassist.ReceiverEngagementEngine
import com.assistant.runtime.*
import kotlin.math.hypot
object ReceiverEngagementContributor : GameplayContributor {
    override val engineName = "ReceiverEngagement"
    override val capabilities = setOf(EngineCapability.SUPPORT, EngineCapability.PASSING)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val d = hypot(frame.passTargetX - frame.ballX, frame.passTargetY - frame.ballY)
        val r = ReceiverEngagementEngine.evaluate(d, frame.bestLaneConfidence * 10f)
        return EngineContribution(engineName, ActionClass.PASS,
            frame.passTargetX.coerceAtLeast(0f), frame.passTargetY.coerceAtLeast(0f),
            (r.engagementBoost / 10f).coerceIn(0f, 1f),
            r.confidence.coerceIn(0f, 1f), 42L)
    }
}
