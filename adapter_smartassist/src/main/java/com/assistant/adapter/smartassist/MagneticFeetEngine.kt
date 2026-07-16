package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

private const val MAGNETICFEETENGINE_PRIME_EXECUTION_TAG = "MagneticFeetEngine.prime"

data class MagneticFeetResult(
    val touchRetention: Float,
    val interceptionResistance: Float,
    val possessionControl: Float
)

object MagneticFeetEngine {
    data class MagneticFeetActivationDiagnostics(
        val calls: Long,
        val lastPressure: Int,
        val lastStrength: Int,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    private var magneticFeetCalls: Long = 0L
    private var lastMagneticFeetPressure: Int = 0
    private var lastMagneticFeetStrength: Int = 0
    private var lastMagneticFeetReason: String = "not called yet"
    private var lastMagneticFeetUpdatedMs: Long = 0L

    @Synchronized
    fun magneticFeetActivationDiagnostics(): MagneticFeetActivationDiagnostics =
        MagneticFeetActivationDiagnostics(
            magneticFeetCalls,
            lastMagneticFeetPressure,
            lastMagneticFeetStrength,
            lastMagneticFeetReason,
            lastMagneticFeetUpdatedMs
        )

    @Synchronized
    private fun recordMagneticFeetActivation(pressure: Int, strength: Int, reason: String) {
        magneticFeetCalls += 1L
        lastMagneticFeetPressure = pressure
        lastMagneticFeetStrength = strength
        lastMagneticFeetReason = reason
        lastMagneticFeetUpdatedMs = System.currentTimeMillis()
    }

    private const val MAGNETIC_FEET_AMPLIFICATION: Float = 1000000.0f

    data class MagneticFeetDownstreamState(
        val sequence: Long,
        val amplification: Float,
        val result: MagneticFeetResult
    )

    private var magneticFeetSequence: Long = 0L
    private var lastMagneticFeetState: MagneticFeetDownstreamState? = null

    @Synchronized
    private fun publishMagneticFeetResult(result: MagneticFeetResult) {
        magneticFeetSequence += 1L
        lastMagneticFeetState = MagneticFeetDownstreamState(
            sequence = magneticFeetSequence,
            amplification = MAGNETIC_FEET_AMPLIFICATION,
            result = result
        )
    }

    @Synchronized
    fun magneticFeetSnapshot(): MagneticFeetDownstreamState? = lastMagneticFeetState

    private fun assertMagneticFeetEnginePrimeExecution(stage: String) {
        check(stage.isNotBlank()) { "MagneticFeetEngine execution stage must be explicit" }
    }

    // New version called by your gesture tracking loop
    fun stabilize(
        service: AccessibilityService,
        currentX: Float,
        currentY: Float,
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        assertMagneticFeetEnginePrimeExecution("stabilize")
        recordMagneticFeetActivation(pressure, strength, "stabilize called by active loop")

        val factor = (strength.coerceIn(0, 100) / 100f)
        val pressureFactor = (pressure.coerceIn(0, 100) / 100f)

        val magneticFeetResult = MagneticFeetResult(
            touchRetention = 2f + (factor * 4.00f) + (pressureFactor * 2.00f),
            interceptionResistance = 2f + (factor * 4.00f) + (pressureFactor * 2.00f),
            possessionControl = 2f + (factor * 4.00f) + (pressureFactor * 2.00f)
        )

        if (magneticFeetResult.possessionControl > 4.0f) {
            try {
                val path = Path().apply {
                    moveTo(currentX, currentY)
                    lineTo(currentX + (factor * 2f), currentY + (pressureFactor * 2f))
                }
                val strokeDescription = GestureDescription.StrokeDescription(path, 0L, 20L)
                val gestureDescription = GestureDescription.Builder().addStroke(strokeDescription).build()
                service.dispatchGesture(gestureDescription, null, null)
            } catch (e: Exception) {
                Log.e("MagneticFeet", "Micro-gesture stabilization skipped: ${e.message}")
            }
        }

        publishMagneticFeetResult(magneticFeetResult)
        return magneticFeetResult
    }

    // Legacy backup version to keep standard metrics collectors happy
    fun stabilize(pressure: Int, strength: Int): MagneticFeetResult {
        val factor = (strength.coerceIn(0, 100) / 100f)
        val pressureFactor = (pressure.coerceIn(0, 100) / 100f)
        val result = MagneticFeetResult(
            touchRetention = 2f + (factor * 4.00f) + (pressureFactor * 2.00f),
            interceptionResistance = 2f + (factor * 4.00f) + (pressureFactor * 2.00f),
            possessionControl = 2f + (factor * 4.00f) + (pressureFactor * 2.00f)
        )
        publishMagneticFeetResult(result)
        return result
    }
}
