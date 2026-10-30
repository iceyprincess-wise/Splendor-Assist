object TrueShotContributor : GameplayContributor {
    override val engineName = "TrueShot"
    override val capabilities = setOf(EngineCapability.ATTACK)
    private val outBuffer = FloatArray(5)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        if (!frame.trusted || !frame.hasBall) return null
        
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
        
        val boost = if (frame.enabled) 0.18f else 0f
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.SHOT,
            targetX = outBuffer[1],
            targetY = outBuffer[2],
            authority = (outBuffer[3] + boost).coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
/* ======
TrueShotContributor Anchor
====== */

/* ========
WingBlockContributor
======== */
