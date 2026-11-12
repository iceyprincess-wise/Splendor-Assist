package com.assistant






































import com.assistant.runtime.*
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor

import com.assistant.runtime.RuntimeFrame




















import kotlin.math.*




import kotlin.math.cos







import kotlin.math.sin















object AgilityContributor : GameplayContributor {
    override val engineName = "Agility"
    override val capabilities = setOf(EngineCapability.MOVEMENT, EngineCapability.SUPPORT)

    // ZERO-ALLOCATION JNI BUFFER: 6 floats produced by native C ABI
    // [0]=stabilityBoost, [1]=controlRetentionBoost, [2]=turnAssist, [3]=shieldAngle, [4]=shieldDuration, [5]=shieldActive
    private val nativeAgilityOut = FloatArray(6)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val observed = observeAgility(frame) ?: return null
        val ballX = observed.x
        val ballY = observed.y

        val success = com.assistant.NativeBridge.nativeComputeAgilityPhysics(
            observed.speed, observed.distance, observed.headingDegrees, observed.confidence, observed.turnIntensity,
            ballX, ballY, observed.opponentX, observed.opponentY,
            nativeAgilityOut,
            100.0f, 220.0f, 500.0f, 1200.0f,
            observed.referenceSpeed, 4.0f, 6.0f, 3.0f, 2.0f,
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
            durationHintMs = durationHintMs,
            aimOriginX = observed.x, aimOriginY = observed.y, aimEntityId = observed.carrierId
        )
    }
}

/* ========
AttackingVectorContributor
======== */
