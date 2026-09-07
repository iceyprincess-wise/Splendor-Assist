package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.TrackedPlayer
import com.assistant.adapter.smartassist.TrueTargetPassingEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * TruePassContributor
 *
 * Upgraded zero-allocation GameplayContributor.
 * - Uses direct indexed array loop over tracked players to eliminate GC allocations.
 * - Leverages TrueTargetPassingEngine.optimizeWithAdvancedTactics for run prediction
 *   and defender density offset.
 */
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
            if (p.isUserTeam && !p.isGoalkeeper) {
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
            TrueTargetPassingEngine.optimize(
                frame.ballX, frame.ballY,
                frame.passTargetX, frame.passTargetY,
                frame.bestLaneConfidence.coerceIn(0f, 1f)
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
