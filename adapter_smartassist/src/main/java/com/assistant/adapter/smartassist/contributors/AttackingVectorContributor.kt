package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.CriticalAttackingVectorEngine
import com.assistant.runtime.*
import kotlin.math.hypot

object AttackingVectorContributor : GameplayContributor {
    override val engineName = "AttackingVector"
    override val capabilities = setOf(EngineCapability.ATTACK)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null

        // Use real GoalDetector coordinates when the detector has seen the goal.
        // Hardcoded 1620/360 was always stale on any non-standard camera zoom.
        val goalDetected = frame.goalDetected && frame.goalConfidence > 0.3f &&
                           frame.goalRightX > frame.goalLeftX
        val gkX         = if (goalDetected) (frame.goalLeftX + frame.goalRightX) * 0.5f  else 1620f
        val gkY         = if (goalDetected) (frame.goalTopY  + frame.goalBottomY) * 0.5f else 360f
        val leftPostX   = if (goalDetected) frame.goalLeftX  else 1620f
        val leftPostY   = if (goalDetected) frame.goalTopY   else 280f
        val rightPostX  = if (goalDetected) frame.goalRightX else 1620f
        val rightPostY  = if (goalDetected) frame.goalBottomY else 440f

        // Gate: only suggest shot from within reasonable range (~600px).
        // bestLaneConfidence is a PASSING lane metric, irrelevant to shot angle.
        val distToGoal = hypot(frame.ballX - gkX, frame.ballY - gkY)
        if (distToGoal > 600f) return null

        val point = CriticalAttackingVectorEngine.computeAbsoluteScoringVector(
            strikerX       = frame.ballX,
            strikerY       = frame.ballY,
            gkX            = gkX,
            gkY            = gkY,
            goalLeftPostX  = leftPostX,
            goalLeftPostY  = leftPostY,
            goalRightPostX = rightPostX,
            goalRightPostY = rightPostY
        )

        // Authority: proximity to goal weighted with defensive clearance
        val proximity  = (1f - distToGoal / 600f).coerceIn(0f, 1f)
        val clearance  = (1f - frame.defenderDensity * 0.5f).coerceIn(0f, 1f)
        val authority  = (proximity * 0.65f + clearance * 0.35f).coerceIn(0f, 1f)

        return EngineContribution(
            engine         = engineName,
            actionClass    = ActionClass.SHOT,
            targetX        = point.x.coerceAtLeast(0f),
            targetY        = point.y.coerceAtLeast(0f),
            authority      = authority,
            confidence     = frame.confidence,
            durationHintMs = 35L
        )
    }
}
