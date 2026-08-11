package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.InstantInterceptEngine
import com.assistant.runtime.*

object InstantInterceptContributor : GameplayContributor {
    override val engineName   = "InstantIntercept"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val r = InstantInterceptEngine.compute(frame)
        if (!r.found) return null
        return EngineContribution(engineName, ActionClass.DEFEND,
            r.targetX, r.targetY, r.authority, frame.confidence, 16L)
    }
}
