object DefenseContributor : GameplayContributor {
    override val engineName = "Defense"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    private val outDominance = FloatArray(4)
    private val outStopper = FloatArray(4)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // UNCONDITIONAL AGGRESSIVE EXECUTION - NO GATES
        
        val defX = frame.ballX - 50.0f
        val defY = frame.ballY
        val screenW = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val screenH = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        
        com.assistant.NativeBridge.nativeComputeDominanceVectors(
            defX, defY, 0f, 0f,
            frame.ballX, frame.ballY, 0f, 0f,
            frame.hasBall, screenW, screenH,
            android.os.SystemClock.uptimeMillis().toInt(), outDominance
        )
        
        com.assistant.NativeBridge.nativeExecuteStopperMatrix(
            defX, defY, 0f, 0f,
            screenW * 0.5f, screenH * 0.5f,
            frame.ballX, frame.ballY, 0f, 0f,
            frame.hasBall, screenW, screenH,
            outStopper
        )
        
        val finalX = outStopper[0].coerceAtLeast(0f)
        val finalY = outStopper[1].coerceAtLeast(0f)
        
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.DEFEND,
            targetX = finalX,
            targetY = finalY,
            authority = frame.defenderDensity.coerceIn(0f, 1f),
            confidence = frame.confidence,
            durationHintMs = 30L
        )
    }
}
/* ======
DefenseContributor Anchor
====== */

/* ========
EvadeContributor
======== */
