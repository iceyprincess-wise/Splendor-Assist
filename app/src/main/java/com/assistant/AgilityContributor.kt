object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    // ZERO-ALLOCATION JNI BUFFER: 6 floats produced by native C ABI
    // [0]=stabilityBoost, [1]=controlRetentionBoost, [2]=turnAssist, [3]=shieldAngle, [4]=shieldDuration, [5]=shieldActive
    private val nativeAgilityOut = FloatArray(6)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        // SPLENDOR_AGILITY_LIVE_TELEMETRY_V1 - feed live telemetry into the native C engine.
        val ballX = frame.ballX
        val ballY = frame.ballY

        // Nearest opponent to the carrier. Defaults keep the engine on its
        // movement-angle shield fallback until a proven defender exists.
        var opponentDistance = (1f - frame.defenderDensity.coerceIn(0f, 1f)) * 300f
        var oppX = ballX
        var oppY = ballY
        try {
            val def = Phase3WorldStateStore.current().defender
            if (def.found && def.distanceToAttacker < Float.MAX_VALUE) {
                opponentDistance = def.distanceToAttacker.coerceIn(0f, 1200f)
                val tracked = def.defender
                if (tracked != null) {
                    oppX = tracked.x
                    oppY = tracked.y
                }
            }
        } catch (_: Throwable) {
        }

        val movementAngleDegrees = if (frame.passTargetX > 0f || frame.passTargetY > 0f) {
            Math.toDegrees(
                atan2(
                    (frame.passTargetY - ballY).toDouble(),
                    (frame.passTargetX - ballX).toDouble()
                )
            ).toFloat()
        } else 0f

        // SPLENDOR_AGILITY_VELOCITY_UNITS_V1 - the engine declares a 0..15
        // velocity space (native_agility_physics.c SPLENDOR_AGILITY_UNITS_V3).
        // This contributor already matched it; the value is clamped explicitly
        // so the contract cannot drift again.
        val playerVelocity =
            (frame.bestLaneConfidence * 10.0f + frame.confidence * 5.0f).coerceIn(0f, 15f)
        // turnIntensity contract is 0..1 (how hard the carrier is turning).
        val turnIntensity = frame.defenderDensity.coerceIn(0f, 1f)
        val possessionConfidence = frame.confidence.coerceIn(0f, 1f)

        val success = com.assistant.NativeBridge.nativeComputeAgilityPhysics(
            playerVelocity, opponentDistance, movementAngleDegrees, possessionConfidence, turnIntensity,
            ballX, ballY, oppX, oppY,
            nativeAgilityOut,
            100.0f, 220.0f, 500.0f, 1200.0f,
            15.0f, 4.0f, 6.0f, 3.0f, 2.0f,
            45.0f, 0.3f, 0.5f
        )

        if (!success || nativeAgilityOut[5] < 0.1f) return null // Native layer determined no active shield/agile move

        val angleRad = Math.toRadians(nativeAgilityOut[3].toDouble())
        val turnAssist = nativeAgilityOut[2].coerceIn(0f, 1f)
        val pushDist = 75.0f + turnAssist * 45.0f
        val calculatedTargetX = (ballX + kotlin.math.cos(angleRad) * pushDist).toFloat().coerceIn(0f, com.assistant.vision.CameraProfile.captureWidthOrFallback())
        val calculatedTargetY = (ballY + kotlin.math.sin(angleRad) * pushDist).toFloat().coerceIn(0f, com.assistant.vision.CameraProfile.captureHeightOrFallback())
        val authority = (nativeAgilityOut[0] / 15.0f).coerceIn(0.1f, 0.95f)
        val confidence = nativeAgilityOut[1].coerceIn(0.1f, 1f)
        val durationHintMs = nativeAgilityOut[4].toLong().coerceIn(16L, 100L)

        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.MOVE,
            targetX = calculatedTargetX,
            targetY = calculatedTargetY,
            authority = authority,
            confidence = confidence,
            durationHintMs = durationHintMs
        )
    }
}
/* ======
AgilityContributor Anchor
====== */

/* ========
AttackingVectorContributor
======== */
