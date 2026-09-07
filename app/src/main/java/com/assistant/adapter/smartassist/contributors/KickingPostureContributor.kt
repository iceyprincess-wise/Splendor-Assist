package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.KickingPostureEngine
import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.TrackedPlayer
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

object KickingPostureContributor : GameplayContributor {
    override val engineName = "KickingPosture"
    override val capabilities = setOf(
        EngineCapability.PASSING,
        EngineCapability.ATTACK,
        EngineCapability.DEFENSE
    )

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        val players = scene.trackedPlayers
        val size = players.size

        var carrier: TrackedPlayer? = null
        var minCarrierDist = Float.MAX_VALUE

        for (i in 0 until size) {
            val p = players[i]
            if (p.isUserTeam && !p.isGoalkeeper) {
                val d = hypot((p.x - frame.ballX).toDouble(), (p.y - frame.ballY).toDouble()).toFloat()
                if (d < minCarrierDist) {
                    minCarrierDist = d
                    carrier = p
                }
            }
        }

        val userX = carrier?.x ?: frame.ballX
        val userY = carrier?.y ?: frame.ballY
        val userVx = carrier?.velocityX ?: 0f
        val userVy = carrier?.velocityY ?: 0f

        val targetX = if (frame.passTargetX > 0f) frame.passTargetX else frame.ballX
        val targetY = if (frame.passTargetY > 0f) frame.passTargetY else frame.ballY

        val result = KickingPostureEngine.evaluateAndCorrect(
            carrierX = userX,
            carrierY = userY,
            carrierVx = userVx,
            carrierVy = userVy,
            targetX = targetX,
            targetY = targetY
        )

        if (result.balanceScore >= 0.95f && !result.requiresAdjustTouch) {
            return null
        }

        val action = if (frame.goalDetected) ActionClass.SHOT else ActionClass.PASS
        val authority = (1f - result.balanceScore).coerceIn(0.3f, 0.85f)

        return EngineContribution(
            engine = engineName,
            actionClass = action,
            targetX = result.correctedX,
            targetY = result.correctedY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = if (result.requiresAdjustTouch) 60L else 32L
        )
    }
}
