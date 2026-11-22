package com.assistant

import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object OverloadPlaystyleContributor : GameplayContributor {
    override val engineName = "OverloadPlaystyle"
    override val capabilities =
        setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT, EngineCapability.PASSING)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null
        if (frame.playerCount <= 0) return null

        // No68-G-D20: zone thirds must use the frame's own capture
        // height, like FrameAssembler and OverloadPlaystyleEngine. The
        // old hardcoded 240f/480f are thirds of 720 only; on the live
        // 640-high capture surface (288x640) they disagree with the
        // engine's own zone by up to ~53 px, so the engine was fed
        // another zone's counts while labelling the ball's zone.
        val pitchH = if (frame.captureHeight > 0f) frame.captureHeight else 720f
        val zoneIndex = when {
            frame.ballY < pitchH * 0.33f -> 0
            frame.ballY > pitchH * 0.67f -> 2
            else -> 1
        }

        val result = OverloadPlaystyleEngine.analyze(
            ballX = frame.ballX,
            ballY = frame.ballY,
            playerCount = frame.playerCount,
            opponentCount = frame.opponentCount,
            defenderDensity = frame.defenderDensity,
            laneConfidence = frame.bestLaneConfidence,
            weHavePossession = frame.hasBall,
            zoneOurs = frame.zones.oursIn(zoneIndex),
            zoneTheirs = frame.zones.theirsIn(zoneIndex),
            // No68-G-D21: pass the frame's known attack direction so the
            // exploit advance follows it (see OverloadPlaystyleEngine).
            attackDirection = frame.attackDirection
        )

        if (result.mode == OverloadMode.IDLE) return null
        if (result.overloadStrength <= 0f) return null

        val action = when {
            result.mode == OverloadMode.DEFENSIVE_SWARM -> ActionClass.DEFEND
            result.switchPlayRecommended -> ActionClass.PASS
            result.zone == OverloadZone.CENTRAL -> ActionClass.MOVE
            else -> ActionClass.CROSS
        }

        return EngineContribution(
            engine = engineName,
            actionClass = action,
            targetX = result.exploitX.coerceAtLeast(0f),
            targetY = result.exploitY.coerceAtLeast(0f),
            authority = result.overloadStrength.coerceIn(0f, 1f),
            confidence = result.confidence.coerceIn(0f, 1f),
            durationHintMs = if (result.mode == OverloadMode.DEFENSIVE_SWARM) 35L else 45L
        )
    }
}
