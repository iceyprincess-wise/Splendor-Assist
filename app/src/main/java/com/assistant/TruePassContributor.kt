package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*





import kotlin.math.hypot






















object TruePassContributor : GameplayContributor {
    override val engineName = "TruePass"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall || frame.viableLaneCount <= 0) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null } ?: return null
        val players = scene.trackedPlayers
        val size = players.size

        var bestReceiver: TrackedPlayer? = null
        var minDistance = Float.MAX_VALUE

        // Zero-allocation indexed loop
        for (i in 0 until size) {
            val p = players[i]
            if (p.isUserTeam && !p.isGoalkeeper && p.lastSeenFrame == scene.frameNumber) {
                val d = hypot((p.x - frame.passTargetX).toDouble(), (p.y - frame.passTargetY).toDouble()).toFloat()
                if (d < minDistance) {
                    minDistance = d
                    bestReceiver = p
                }
            }
        }

        val r = if (bestReceiver != null && minDistance < 120f) {
            TrueTargetPassingEngine.optimizeWithAdvancedTactics(
                frame.ballX, frame.ballY,
                bestReceiver.x, bestReceiver.y,
                bestReceiver.velocityX, bestReceiver.velocityY,
                frame.defenderDensity
            )
        } else {
            // No67-D11: retention 1.0f — the fallback target is the real
            // pass target, not a fraction of the way there (at low
            // confidence the old fraction was a noise target ~30 px from
            // the ball). Authority is still confidence-shaped.
            TrueTargetPassingEngine.optimize(
                frame.ballX, frame.ballY,
                frame.passTargetX, frame.passTargetY,
                1.0f
            )
        }

        val authority = ((1f - r.interceptionRisk) * frame.bestLaneConfidence.coerceAtLeast(0.3f)).coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = r.correctedX,
            targetY = r.correctedY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}

