object ShotOpportunityContributor : GameplayContributor {
    override val engineName = "ShotOpportunity"
    override val capabilities = setOf(EngineCapability.ATTACK)
    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        val r = ShotOpportunityAnalysisEngine.analyze(
            (com.assistant.vision.CameraProfile.captureWidthOrFallback() - frame.ballX).coerceAtLeast(0f), frame.defenderDensity)
        if (r.openSideScore <= 0f) return null
        return EngineContribution(engineName, ActionClass.SHOT,
            frame.ballX, frame.ballY,
            (r.openSideScore / 10f).coerceIn(0f, 1f), r.confidence.coerceIn(0f, 1f), 35L)
    }
}
/* ======
ShotOpportunityContributor Anchor
====== */

/* ========
SmartAssistUltimateCorrectorContributor
======== */
/**
 * SmartAssistUltimateCorrectorContributor
 *
 * Zero-allocation always-on contributor for correcting eFootball Smart Assist drift.
 * Eliminates redundant null checks and object creations inside 60 FPS hot paths.
 */
