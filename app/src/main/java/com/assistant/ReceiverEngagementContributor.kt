package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*





import kotlin.math.hypot






















object ReceiverEngagementContributor : GameplayContributor {
    override val engineName = "ReceiverEngagement"
    override val capabilities = setOf(EngineCapability.SUPPORT, EngineCapability.PASSING)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        // Without a viable lane, passTargetX/Y = 0 → corrupted distance calculation
        if (frame.viableLaneCount <= 0) return null
        val d = hypot(frame.passTargetX - frame.ballX, frame.passTargetY - frame.ballY)
        val r = ReceiverEngagementEngine.evaluate(d, frame.bestLaneConfidence * 10f)
        return EngineContribution(engineName, ActionClass.PASS,
            frame.passTargetX.coerceAtLeast(0f), frame.passTargetY.coerceAtLeast(0f),
            (r.engagementBoost / 10f).coerceIn(0f, 1f),
            r.confidence.coerceIn(0f, 1f), 42L)
    }
}

/* ========
ShotAnticipationContributor
======== */
/* Threat-anticipation defensive claim. Frame-derived so it never needs a
   ThreatDecision from another engine, preserving isolation. */
