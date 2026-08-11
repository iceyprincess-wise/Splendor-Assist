package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.BallRetentionShieldEngine
import com.assistant.runtime.*

object BallRetentionShieldContributor : GameplayContributor {
    override val engineName   = "BallRetentionShield"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val r = BallRetentionShieldEngine.compute(frame)
        if (!r.found) return null
        return EngineContribution(engineName, ActionClass.MOVE,
            r.shieldX, r.shieldY, r.authority, frame.confidence, 24L)
    }
}
