package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object ShotAnticipationContributor : GameplayContributor {
    override val engineName = "ShotAnticipation"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.KEEPER)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.defenderDensity < 0.4f) return null
        return EngineContribution(engineName, ActionClass.DEFEND,
            frame.ballX, (frame.ballY - 60f).coerceAtLeast(0f),
            (frame.defenderDensity * 0.85f).coerceIn(0f, 1f), frame.confidence, 30L)
    }
}

/* ========
KickingPostureContributor
======== */
