package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




























object ShotOpportunityContributor : GameplayContributor {
    override val engineName = "ShotOpportunity"
    override val capabilities = setOf(EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        if (!frame.goalDetected) return null
        // No67-D31: distance to the goal when detected (the old value was
        // the distance to the right edge of the capture — meaningless when
        // the goal is at the left end).
        val goalDistance = if (frame.goalDetected) hypot(frame.goalCenterX - frame.ballX, frame.goalCenterY - frame.ballY)
            else (com.assistant.vision.CameraProfile.captureWidthOrFallback() - frame.ballX).coerceAtLeast(0f)
        val r = ShotOpportunityAnalysisEngine.analyze(goalDistance, frame.defenderDensity)
        if (r.openSideScore <= 0f) return null
        // No67-D33: the old target was the ball itself, so chooseRequest saw
        // a zero drag vector and dropped every dispatch. Aim at the detected
        // goal centre; without a detected goal there is no shot to take.
        // (Open-side aim instead of centre is Ed's call — Q67-26; centre is
        // the least-assumptive aim.)
        return EngineContribution(engineName, ActionClass.SHOT,
            frame.goalCenterX.coerceAtLeast(0f), frame.goalCenterY.coerceAtLeast(0f),
            (r.openSideScore / 4f).coerceIn(0f, 1f), r.confidence.coerceIn(0f, 1f), 35L)
    }
}

/* ========
SmartAssistUltimateCorrectorContributor
======== */
/**
 * SmartAssistUltimateCorrectorContributor
 *
 * Zero-allocation always-on contributor for correcting eFootball Smart Assist drift.
 * Eliminates redundant null checks and object creations inside 60 FPS hot paths.
 */
