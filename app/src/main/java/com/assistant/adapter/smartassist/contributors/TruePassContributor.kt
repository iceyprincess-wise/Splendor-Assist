package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.TrueTargetPassingEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * TruePassContributor  (rewritten)
 *
 * BUG FIXED: old code passed bestLaneConfidence * 10f as retention argument
 * to TrueTargetPassingEngine.optimize(), causing 10x overshoot past the
 * target player on every single pass.
 *
 * NEW BEHAVIOUR:
 *   - When a user receiver is found within 120px of the pass target:
 *       uses TrueTargetPassingEngine.optimizeWithRunPrediction() which
 *       predicts where the receiver will be when the ball arrives and aims
 *       there — defeating SA interception where the receiver runs AWAY
 *       from the ball path.
 *   - Otherwise:
 *       uses TrueTargetPassingEngine.optimize() with retention clamped
 *       correctly to [0, 1].
 */
object TruePassContributor : GameplayContributor {
    override val engineName = "TruePass"
    override val capabilities = setOf(EngineCapability.PASSING, EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall || frame.viableLaneCount <= 0) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val receiver = scene?.trackedPlayers
            ?.filter { it.isUserTeam && !it.isGoalkeeper }
            ?.minByOrNull {
                hypot(
                    (it.x - frame.passTargetX).toDouble(),
                    (it.y - frame.passTargetY).toDouble()
                )
            }

        val r = if (receiver != null &&
            hypot(
                (receiver.x - frame.passTargetX).toDouble(),
                (receiver.y - frame.passTargetY).toDouble()
            ) < 120.0
        ) {
            TrueTargetPassingEngine.optimizeWithRunPrediction(
                frame.ballX, frame.ballY,
                receiver.x, receiver.y,
                receiver.velocityX, receiver.velocityY
            )
        } else {
            TrueTargetPassingEngine.optimize(
                frame.ballX, frame.ballY,
                frame.passTargetX, frame.passTargetY,
                frame.bestLaneConfidence.coerceIn(0f, 1f)
            )
        }

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.PASS,
            targetX = r.correctedX.coerceAtLeast(0f),
            targetY = r.correctedY.coerceAtLeast(0f),
            authority = ((1f - r.interceptionRisk) *
                frame.bestLaneConfidence.coerceAtLeast(0.3f)).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 45L
        )
    }
}
