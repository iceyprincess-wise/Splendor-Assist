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
        recordMagneticFeetActivation(pressure, strength, "stabilize called by active loop - OMEGA MODE AMPLIFIED")

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
                
                // AMPLIFICATION: Increased offset limit from 8.0f to 24.0f so the game actually registers the physical drag
                val offsetLimit = 24.0f * factor

                // AMPLIFICATION: Increased noise variance slightly to force the game client to update stick vectors
                val horizontalNoise = Random.nextFloat() * 3.0f - 1.5f // +/- 1.5 pixel micro-variance
                val verticalNoise = Random.nextFloat() * 3.0f - 1.5f

                val microDeltaX = (cos(cycleAngle) * offsetLimit).toFloat() + horizontalNoise
                val microDeltaY = (sin(cycleAngle) * offsetLimit).toFloat() + verticalNoise

                val path = Path().apply {
                    moveTo(currentX, currentY)
                    lineTo(currentX + microDeltaX, currentY + microDeltaY)
                }

                // AMPLIFICATION: Changed from 11-14ms to 17-22ms. 
                // This aligns perfectly with a 60Hz frame refresh (16.6ms), ensuring the game engine processes the touch.
                val adaptiveDuration = Random.nextLong(17, 23) 
                val microStartDelay = Random.nextLong(0, 2)    

                val strokeDescription = GestureDescription.StrokeDescription(path, microStartDelay, adaptiveDuration)
                val gestureDescription = GestureDescription.Builder().addStroke(strokeDescription).build()
                service.dispatchGesture(gestureDescription, null, null)
            } catch (e: Exception) {
                Log.e("MagneticFeet", "Micro-gesture stabilization skipped: ${e.message}")
            }
        }

        // Tag logging to prevent "Unused Private Constant" compilation warning for MAGNETICFEETENGINE_PRIME_EXECUTION_TAG
        Log.d(MAGNETICFEETENGINE_PRIME_EXECUTION_TAG, "Stabilize cycle processed successfully.")

        publishMagneticFeetResult(magneticFeetResult)
        return magneticFeetResult
    }

    // Legacy backup version to keep standard metrics/telemetry collectors running (Fully completed to prevent trailing syntax errors)
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
