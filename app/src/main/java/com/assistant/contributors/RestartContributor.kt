package com.assistant.contributors

import com.assistant.RestartEngine
import com.assistant.RestartKind
import com.assistant.runtime.*

/*
 * G-D — Restart contributor.
 *
 * Our goal kick / throw-in / kickoff: build up through the B8 passing
 * target (frame.passTarget — the passing-graph receiver, not the
 * crossing lane). Modest authority (0.50): a restart is a rehearsed
 * shape, not a forced ball — when no passing target exists the
 * contributor is silent and the ordinary passing contributors own the
 * frame. Silent in open play and on the opponent's restarts.
 */
object RestartContributor : GameplayContributor {
    override val engineName = "Restart"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.SUPPORT)

    override fun fastGate(frame: RuntimeFrame): Boolean =
        frame.trusted && frame.hasBall

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val detection = RestartEngine.detect(frame)
        if (detection.kind == RestartKind.OPEN_PLAY) return null
        if (frame.passTargetX <= 0f && frame.passTargetY <= 0f) return null
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = frame.passTargetX.coerceIn(0f, frame.captureWidth),
            targetY = frame.passTargetY.coerceIn(0f, frame.captureHeight),
            authority = 0.50f,
            confidence = frame.confidence,
            durationHintMs = 45L,
            targetEntityId = frame.passTargetId
        )
    }
}
