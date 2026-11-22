package com.assistant

import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object ShotAnticipationContributor : GameplayContributor {
    override val engineName = "ShotAnticipation"
    override val capabilities = setOf(EngineCapability.DEFENSE, EngineCapability.KEEPER)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        if (frame.panic) return null // silent while user panic mode is on (as BallPress does)
        if (frame.defenderDensity < 0.4f) return null
        // No68-F-D5: the old target used ballX raw and ballY only
        // lower-bounded — a stale out-of-frame ball could aim off-screen.
        // Clamp both to the capture (trusted guarantees captureWidth /
        // captureHeight are finite and > 0). Direction is unchanged for
        // any on-screen ball.
        return EngineContribution(engineName, ActionClass.DEFEND,
            frame.ballX.coerceIn(0f, frame.captureWidth), frame.ballY.coerceIn(0f, frame.captureHeight), // No67-D35: the old -60px offset was direction-blind (always up-screen); target the ball until a goal-aware offset is decided (Q67-16).
            (frame.defenderDensity * 0.85f).coerceIn(0f, 1f), frame.confidence, 30L)
    }
}
