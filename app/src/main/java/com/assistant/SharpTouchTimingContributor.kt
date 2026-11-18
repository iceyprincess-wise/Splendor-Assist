package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*





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
            // No68-I-D10: the keeper is not a sharp-touch actor — the old
            // scan took the nearest user-team player INCLUDING the keeper,
            // so a keeper-nearest frame emitted a PASS/SHOT/DEFEND from the
            // keeper's position. KickingPosture already excludes the keeper.
            if (p.isUserTeam && !p.isGoalkeeper) {
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

        // No68-I-D11: the old gate only returned null for optimalFrameOffset
        // > 1, so a frame the engine itself calls non-executable
        // (!executeNow, best frame NOW, ball beyond the sweet spot and
        // receding) still emitted a 0.3-authority contribution. Only two
        // cases may contribute: executeNow, or the touch is optimal next
        // frame (offset 1 — the 16 ms swipe lands on that frame).
        if (!result.executeNow && result.optimalFrameOffset != 1) {
            return null
        }

        val action = when {
            frame.hasBall && frame.goalDetected -> ActionClass.SHOT
            frame.hasBall -> ActionClass.PASS
            else -> ActionClass.DEFEND
        }

        // No68-I-D12: same class as No68-I-D1 — a SHOT must aim at the goal,
        // not at the pass target or the ball.
        // No68-I-D43 (reconstruction fix): the first tracer's D12 applied the
        // goal-centre aim whenever goalDetected, including DEFEND frames
        // (!hasBall), where the target must stay the ball — a DEFEND drag
        // toward the goal centre sends the defender goal-side instead of to
        // the ball. Gate the goal aim on the action actually being SHOT.
        val targetX = if (action == ActionClass.SHOT) frame.goalCenterX else if (frame.passTargetX > 0f) frame.passTargetX else frame.ballX
        val targetY = if (action == ActionClass.SHOT) frame.goalCenterY else if (frame.passTargetY > 0f) frame.passTargetY else frame.ballY

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

/* ========
FrameDropStabilizer
======== */
/**
 * High-precision Frame Drop Stabilizer optimized for 60Hz/120Hz display refresh cycles.
 * Operates with zero-allocation in the hot-path to strictly prevent GC thrashing.
 */
