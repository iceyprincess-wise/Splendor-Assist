package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.SharpTouchTimingEngine
import com.assistant.adapter.smartassist.TrackedPlayer
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

object SharpTouchTimingContributor : GameplayContributor {
    override val engineName = "SharpTouchTiming"
    override val capabilities = setOf(
        EngineCapability.ATTACK,
        EngineCapability.PASSING,
        EngineCapability.DEFENSE
    )

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        val players = scene.trackedPlayers
        val size = players.size

        var activePlayer: TrackedPlayer? = null
        var minDist = Float.MAX_VALUE

        for (i in 0 until size) {
            val p = players[i]
            if (p.isUserTeam) {
                val d = hypot((p.x - frame.ballX).toDouble(), (p.y - frame.ballY).toDouble()).toFloat()
                if (d < minDist) {
                    minDist = d
                    activePlayer = p
                }
            }
        }

        if (activePlayer == null || minDist > 180f) return null

        val result = SharpTouchTimingEngine.evaluateTiming(
            ballX = frame.ballX,
            ballY = frame.ballY,
            ballVx = frame.ballVelocityX,
            ballVy = frame.ballVelocityY,
            playerX = activePlayer.x,
            playerY = activePlayer.y,
            playerVx = activePlayer.velocityX,
            playerVy = activePlayer.velocityY
        )

        if (!result.executeNow && result.optimalFrameOffset > 1) {
            return null
        }

        val action = when {
            frame.hasBall && frame.goalDetected -> ActionClass.SHOT
            frame.hasBall -> ActionClass.PASS
            else -> ActionClass.DEFENSE
        }

        val targetX = if (frame.passTargetX > 0f) frame.passTargetX else frame.ballX
        val targetY = if (frame.passTargetY > 0f) frame.passTargetY else frame.ballY

        return EngineContribution(
            engine = engineName,
            actionClass = action,
            targetX = targetX,
            targetY = targetY,
            authority = result.timingAuthority,
            confidence = frame.confidence,
            durationHintMs = (result.optimalFrameOffset * 16L).coerceAtLeast(16L)
        )
    }
}
