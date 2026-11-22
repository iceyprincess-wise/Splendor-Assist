package com.assistant

import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

object TrueShotContributor : GameplayContributor {
    override val engineName = "TrueShot"
    override val capabilities = setOf(EngineCapability.ATTACK)
    // No68-F-D10: trusted implies enabled (RuntimeFrame.trusted requires
    // enabled), so the +0.18 boost is unconditional, not conditional. The
    // value itself is unchanged (a tactical number, not re-tuned); only
    // the dead else-branch is gone.
    private const val ENABLED_BOOST = 0.18f

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        // No68-F-D11: no detected goal, no shot. Without this gate the
        // native falls back to aiming at (screenW, ballY) — the right edge
        // at ball height, not a goal — and the "invalid gate" below could
        // never fire (the native always writes outBuffer[0] = 1.0), so
        // TrueShot was the only SHOT contributor that fired blind. The
        // native's own onTarget signal (outBuffer[4], goalDetMask) exists
        // but is never read by this file; the frame's goalDetected is the
        // same input, checked here directly.
        if (!frame.goalDetected) return null
        // No68-F-D12: per-call local buffer (D34 precedent for
        // ShotOpportunityAnalysisEngine) instead of shared mutable state
        // on this singleton.
        val outBuffer = FloatArray(5)

        com.assistant.NativeBridge.nativeTrueShotCompute(
            frame.ballX, frame.ballY,
            frame.goalLeftX, frame.goalRightX,
            frame.goalTopY, frame.goalBottomY,
            frame.goalkeeperX, frame.goalkeeperVisible, frame.goalDetected,
            frame.defenderDensity,
            com.assistant.vision.CameraProfile.captureWidthOrFallback(),
            com.assistant.vision.CameraProfile.captureHeightOrFallback(),
            outBuffer
        )
        
        if (outBuffer[0] < 0.5f) return null // Invalid gate

        val boost = ENABLED_BOOST
        // B2-1 (item 23): align the duration hint to ShotTypeEngine — the
        // same rule ShotContributor already uses. The 30L literal matched
        // FINESSE only: close-range POWER wants 35L (this fired 5ms soft)
        // and keeper-stranded CHIP wants 25L (this fired 5ms long). Aim
        // stays the native outBuffer aim (B1's domain); duration only.
        val durationHintMs = ShotTypeEngine.select(frame, outBuffer[1], outBuffer[2])?.durationHintMs ?: 30L
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            targetX = outBuffer[1],
            targetY = outBuffer[2],
            authority = (outBuffer[3] + boost).coerceIn(0f, 1f),
            // No68-F-D19: same weakest-link rule as ShotContributor after
            // Q67-18 (D13) — a weak goal sighting must discount the shot,
            // not be trusted at full frame confidence.
            confidence = minOf(frame.confidence, frame.goalConfidence).coerceIn(0f, 1f),
            durationHintMs = durationHintMs
        )
    }
}
