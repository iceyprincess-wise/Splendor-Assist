object BallRetentionShieldContributor : GameplayContributor {
    override val engineName   = "BallRetentionShield"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.DEFENSE)
    
    // ZERO-ALLOCATION JNI BUFFER: 5 floats produced by native C ABI
    // [0]=active, [1]=shieldX, [2]=shieldY, [3]=authority, [4]=risk
    private val nativeBuffer = FloatArray(5)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // SPLENDOR_BALL_RETENTION_REAL_TELEMETRY_V1 - feed the frame's real
        // telemetry. The previous call passed only three values and the bridge
        // invented the rest (confidence 1.0, 3/3 lanes, zeroed zones), so this
        // engine could never see a blocked lane or a low-confidence frame.
        if (!frame.trusted || !frame.hasBall) return null
        val zones = frame.zones
        val success = com.assistant.NativeBridge.nativeComputeBallRetention(
            hasBall = frame.hasBall,
            trusted = frame.trusted,
            confidence = frame.confidence,
            ballX = frame.ballX, ballY = frame.ballY,
            viableLaneCount = frame.viableLaneCount,
            laneCount = frame.laneCount,
            passTargetX = frame.passTargetX, passTargetY = frame.passTargetY,
            zonesLeftTheirs = zones.leftTheirs,
            zonesMidTheirs = zones.midTheirs,
            zonesRightTheirs = zones.rightTheirs,
            defenderDensity = frame.defenderDensity,
            resultBuffer = nativeBuffer
        )

        if (!success || nativeBuffer[0] < 0.1f) return null // Active flag check
        
        val authority = nativeBuffer[3].coerceIn(0.1f, 1.0f)
        val confidence = (1.0f - nativeBuffer[4]).coerceIn(0.1f, 1.0f)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = nativeBuffer[1].coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback()),
            targetY = nativeBuffer[2].coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback()),
            authority = authority,
            confidence = confidence,
            durationHintMs = 15L // Maximum reaction speed
        )
    }
}
/* ======
BallRetentionShieldContributor Anchor
====== */

/* ========
BuildUpPressContributor
======== */
