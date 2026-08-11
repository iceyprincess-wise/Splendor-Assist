package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.SceneTracker
import com.assistant.adapter.smartassist.TrueCrossEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * TrueCrossContributor
 *
 * Run-predicted cross: aims at where the receiver WILL BE when the ball arrives.
 *
 * SA cross interception root cause:
 *   SA aims at the receiver's CURRENT position. The receiver is always running
 *   into the box. By the time the lofted ball arrives, the receiver has moved
 *   on and a defender has filled that spot — intercepted every time.
 *
 * TrueCross leads the receiver run so the ball meets them in motion.
 * +0.14 authority boost when SmartAssist corrections are active.
 */
object TrueCrossContributor : GameplayContributor {
    override val engineName = "TrueCross"
    override val capabilities = setOf(EngineCapability.ATTACK, EngineCapability.PASSING)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (frame.viableLaneCount <= 0 && frame.bestLaneConfidence <= 0f) return null

        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val receiver = scene?.trackedPlayers
            ?.filter { it.isUserTeam && !it.isGoalkeeper }
            ?.minByOrNull {
                hypot(
                    (it.x - frame.passTargetX).toDouble(),
                    (it.y - frame.passTargetY).toDouble()
                )
            }

        val goalCX = if (frame.goalDetected)
            (frame.goalLeftX + frame.goalRightX) * 0.5f else 1650f
        val goalCY = if (frame.goalDetected)
            (frame.goalTopY + frame.goalBottomY) * 0.5f else frame.ballY

        val result = TrueCrossEngine.compute(
            frame.ballX, frame.ballY,
            receiver?.x ?: frame.passTargetX,
            receiver?.y ?: frame.passTargetY,
            receiver?.velocityX ?: 0f,
            receiver?.velocityY ?: 0f,
            goalCX, goalCY,
            frame.bestLaneConfidence.coerceAtLeast(0.1f)
        ) ?: return null

        val boost = if (frame.enabled) 0.14f else 0f
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.CROSS,
            targetX = result.targetX,
            targetY = result.targetY,
            authority = (result.confidence + boost).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 42L
        )
    }
}
