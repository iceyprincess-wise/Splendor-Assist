package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object KeeperFeedbackContributor : GameplayContributor {
    override val engineName = "KeeperFeedback"
    override val capabilities = setOf(EngineCapability.KEEPER, EngineCapability.DEFENSE)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || frame.hasBall) return null
        // Ball must be visible to react to it
        if (frame.ballX <= 0f && frame.ballY <= 0f) return null

        // Use actual goalkeeper position when the detector has found one,
        // otherwise aim at ball position (intercepting path)
        //
        // Item 12 (B1): when the keeper is NOT visible, the old target
        // was the ball itself — no range, threat or goal-line clamp, so
        // a midfield ball dragged the keeper out of goal. Clamp the
        // blind target into the goal area instead: X inside the goal
        // span (calibrated posts when valid, else the 0.32–0.68 span),
        // Y no higher than the six-yard top (0.75h). The keeper holds
        // his line instead of chasing a ball he cannot see.
        val w = if (frame.captureWidth > 0f) frame.captureWidth
            else com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val h = if (frame.captureHeight > 0f) frame.captureHeight
            else com.assistant.vision.CameraProfile.captureHeightOrFallback()
        val posts = com.assistant.GoalPostCalibrationEngine.latest()
        val spanLeft = if (posts.valid) posts.leftPostX else w * 0.32f
        val spanRight = if (posts.valid) posts.rightPostX else w * 0.68f
        val targetX = if (frame.goalkeeperVisible && frame.goalkeeperX > 0f)
            frame.goalkeeperX else frame.ballX.coerceIn(spanLeft, spanRight)
        val targetY = if (frame.goalkeeperVisible && frame.goalkeeperY > 0f)
            frame.goalkeeperY else frame.ballY.coerceAtLeast(h * 0.75f)

        // Authority: scale with how much threat is present — but never require
        // defenderDensity >= 0.5f, which silenced the keeper on clean shots
        val authority = (0.35f + frame.defenderDensity * 0.65f).coerceIn(0f, 1f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.KEEPER,
            targetX = targetX,
            targetY = targetY,
            authority = authority,
            confidence = frame.confidence,
            durationHintMs = 28L
        )
    }
}

/* SPLENDOR_P9_ORPHAN_DOC: a trailing MagneticFeet doc block stood here
 * describing a contributor whose code does not exist in this file (the
 * live MagneticFeetContributor is MagneticFeetContributor.kt). Removed:
 * a doc with no code after it is decorative and misleading. */
