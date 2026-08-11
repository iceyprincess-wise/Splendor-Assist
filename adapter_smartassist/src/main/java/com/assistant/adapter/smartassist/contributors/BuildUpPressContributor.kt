package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.BuildUpPressEngine
import com.assistant.runtime.*

object BuildUpPressContributor : GameplayContributor {
    override val engineName   = "BuildUpPress"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val r = BuildUpPressEngine.compute(frame)
        if (!r.found) return null
        return EngineContribution(engineName, ActionClass.DEFEND,
            r.targetX, r.targetY, r.authority, frame.confidence, 20L)
    }
}
