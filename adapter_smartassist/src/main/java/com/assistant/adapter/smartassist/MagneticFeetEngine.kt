package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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

    // Main stabilization loop called by the active touch-tracking system
    fun stabilize(
        service: AccessibilityService,
        currentX: Float,
        currentY: Float,
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        assertMagneticFeetEnginePrimeExecution("stabilize")
        recordMagneticFeetActivation(pressure, strength, "stabilize called by active loop - OMEGA MODE")

        val factor = (strength.coerceIn(0, 100) / 100f)
        val pressureFactor = (pressure.coerceIn(0, 100) / 100f)

        // Mathematical Scaling Upgraded to Absolute Ceiling Level 10.0f for Max Game Advantage
        val rawRetention = 5f + (factor * 4.00f) + (pressureFactor * 2.00f)
        val touchRetention = rawRetention.coerceIn(2.0f, 10.0f)

        val rawResistance = 5f + (factor * 4.00f) + (pressureFactor * 2.00f)
        val interceptionResistance = rawResistance.coerceIn(2.0f, 10.0f)

        val rawControl = 5f + (factor * 4.00f) + (pressureFactor * 2.00f)
        val possessionControl = rawControl.coerceIn(2.0f, 10.0f)

        val magneticFeetResult = MagneticFeetResult(
            touchRetention = touchRetention,
            interceptionResistance = interceptionResistance,
            possessionControl = possessionControl
        )

        // Low-latency micro-adjustment stroke injection (Dynamic Drift Damping)
        if (magneticFeetResult.possessionControl > 4.0f) {
            try {
                // Micro-step vector rotation (360-degree cyclical correction offset)
                val cycleAngle = (magneticFeetCalls % 360) * (Math.PI / 180.0)
                val offsetLimit = 8.0f * factor
                
                // Introduce dynamic humanization noise to break machine pattern footprints
                val horizontalNoise = Random.nextFloat() * 1.4f - 0.7f // +/- 0.7 pixel micro-variance
                val verticalNoise = Random.nextFloat() * 1.4f - 0.7f
                
                val microDeltaX = (cos(cycleAngle) * offsetLimit).toFloat() + horizontalNoise
                val microDeltaY = (sin(cycleAngle) * offsetLimit).toFloat() + verticalNoise

                val path = Path().apply {
                    moveTo(currentX, currentY)
                    // Dynamic micro-vector line targeting the physical sweet-spot
                    lineTo(currentX + microDeltaX, currentY + microDeltaY)
                }

                // Variable timing humanized tightly between 11ms and 14ms to mask fixed 12ms signatures
                val adaptiveDuration = Random.nextLong(11, 15) // 11ms to 14ms bounds
                val microStartDelay = Random.nextLong(0, 2)    // Scrambles packet timing rhythm
                
                val strokeDescription = GestureDescription.StrokeDescription(path, microStartDelay, adaptiveDuration)
                val gestureDescription = GestureDescription.Builder().addStroke(strokeDescription).build()
                service.dispatchGesture(gestureDescription, null, null)
            } catch (e: Exception) {
                Log.e("MagneticFeet", "Micro-gesture stabilization skipped: ${e.message}")
            }
        }

        publishMagneticFeetResult(magneticFeetResult)
        return magneticFeetResult
    }

    // Legacy backup version to keep standard metrics/telemetry collectors running
    fun stabilize(pressure: Int, strength: Int): MagneticFeetResult {
        val factor = (strength.coerceIn(0, 100) / 100f)
        val pressureFactor = (pressure.coerceIn(0, 100) / 100f)

        // Match physical limit of 10.0f on the backup metrics endpoint
        val rawRetention = 5f + (factor * 4.00f) + (pressureFactor * 2.00f)
        val touchRetention = rawRetention.coerceIn(2.0f, 10.0f)

        val rawResistance = 5f + (factor * 4.00f) + (pressureFactor * 2.00f)
        val interceptionResistance = rawResistance.coerceIn(2.0f, 10.0f)

        val rawControl = 5f + (factor * 4.00f) + (pressureFactor * 2.00f)
        val possessionControl = rawControl.coerceIn(2.0f, 10.0f)

        val result = MagneticFeetResult(
            touchRetention = touchRetention,
            interceptionResistance = interceptionResistance,
            possessionControl = possessionControl
        )
        publishMagneticFeetResult(result)
        return result
    }
}
