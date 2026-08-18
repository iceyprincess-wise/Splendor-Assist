package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.OverloadMode
import com.assistant.adapter.smartassist.OverloadPlaystyleEngine
import com.assistant.adapter.smartassist.OverloadZone
import com.assistant.runtime.*

/*
 * Overload playstyle: primary mode is DEFENSIVE SWARM -- when the opponent
 * carries the ball, collapse numbers onto the carrier to deny build-up passes.
 * Hybrid inverse (ATTACKING_EXPLOIT) applies on regained possession.
 */
object OverloadPlaystyleContributor : GameplayContributor {
    override val engineName = "OverloadPlaystyle"
    override val capabilities =
        setOf(EngineCapability.DEFENSE, EngineCapability.MOVEMENT, EngineCapability.PASSING)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null
        if (frame.playerCount <= 0) return null

        val zoneIndex = when {
            frame.ballY < 240f -> 0
            frame.ballY > 480f -> 2
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
            zoneTheirs = frame.zones.theirsIn(zoneIndex)
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
