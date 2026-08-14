package com.assistant.adapter.smartassist.contributors

import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

object ShotContributor : GameplayContributor {
    override val engineName = "Shot"
    override val capabilities = setOf(EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        // Target: goal mouth center. Fall back to screen-right estimate when
        // GoalDetector hasn't confirmed a bounding box yet.
        val goalCenterX = if (frame.goalDetected && frame.goalConfidence > 0.3f)
            (frame.goalLeftX + frame.goalRightX) * 0.5f else 1650f
        val goalCenterY = if (frame.goalDetected && frame.goalConfidence > 0.3f)
            (frame.goalTopY + frame.goalBottomY) * 0.5f else frame.ballY

        // Gate: only suggest shot within ~550px of goal (too far = no chance)
        val distToGoal = hypot(frame.ballX - goalCenterX, frame.ballY - goalCenterY)
        if (distToGoal > 680f  // PHASE4B: 30fps hybrid gives accurate goal detection farther out) return null

        // Authority: closer to goal + fewer defenders blocking = higher
        val proximity  = (1f - distToGoal / 550f).coerceIn(0f, 1f)
        val clearance  = (1f - frame.defenderDensity * 0.6f).coerceIn(0f, 1f)
        val authority  = (proximity * 0.7f + clearance * 0.3f).coerceIn(0f, 1f)

        return EngineContribution(
            engine          = engineName,
            actionClass     = ActionClass.SHOT,
            targetX         = goalCenterX,
            targetY         = goalCenterY,
            authority       = authority,
            confidence      = frame.confidence,
            durationHintMs  = 35L
        )
    }
}
