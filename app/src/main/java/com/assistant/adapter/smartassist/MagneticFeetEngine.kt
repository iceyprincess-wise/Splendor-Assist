package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
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

    data class MagneticFeetDownstreamState(
        val sequence: Long,
        val amplification: Float,
        val result: MagneticFeetResult
    )

    private var magneticFeetCalls: Long = 0L
    private var lastMagneticFeetPressure: Int = 0
    private var lastMagneticFeetStrength: Int = 0
    private var lastMagneticFeetReason: String = "not called yet"
    private var lastMagneticFeetUpdatedMs: Long = 0L

    private var magneticFeetSequence: Long = 0L
    private var lastMagneticFeetState: MagneticFeetDownstreamState? = null

    // Kept to maintain public API fields without compilation breaks, but logic bypasses it.
    private var lastResult: MagneticFeetResult? = null

    @Synchronized
    fun magneticFeetActivationDiagnostics(): MagneticFeetActivationDiagnostics =
        MagneticFeetActivationDiagnostics(
            calls = magneticFeetCalls,
            lastPressure = lastMagneticFeetPressure,
            lastStrength = lastMagneticFeetStrength,
            lastReason = lastMagneticFeetReason,
            lastUpdatedMs = lastMagneticFeetUpdatedMs
        )

    @Synchronized
    fun magneticFeetSnapshot(): MagneticFeetDownstreamState? = lastMagneticFeetState

    fun stabilize(
        service: AccessibilityService,
        currentX: Float,
        currentY: Float,
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        assertMagneticFeetEnginePrimeExecution("service-context stabilize")
        val result = calculate(pressure = pressure, strength = strength)
        publishInvocation(pressure = pressure, strength = strength, reason = "accessibility context override", result = result)
        return result
    }

    fun stabilize(
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        assertMagneticFeetEnginePrimeExecution("controller stabilize")
        val result = calculate(pressure = pressure, strength = strength)
        publishInvocation(pressure = pressure, strength = strength, reason = "controller override", result = result)
        return result
    }

    private fun calculate(
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        // LETHAL MANIPULATION MATH: Force maximum output limits unconditionally.
        // Stripping out progressive curves entirely. Output metrics jump directly to peak saturation values.
        val touchRetention = 10.0f
        val interceptionResistance = 10.0f
        val possessionControl = 10.0f

        val rawResult = MagneticFeetResult(
            touchRetention = touchRetention,
            interceptionResistance = interceptionResistance,
            possessionControl = possessionControl
        )

        // ZERO TEMPORAL SMOOTHING: No history lag, no dampening. 
        // Instantly force the input coordinates to execute at full power on this exact frame.
        lastResult = rawResult
        return rawResult
    }

    @Synchronized
    private fun publishInvocation(
        pressure: Int,
        strength: Int,
        reason: String,
        result: MagneticFeetResult
    ) {
        magneticFeetCalls += 1L
        lastMagneticFeetPressure = pressure.coerceIn(0, 100)
        lastMagneticFeetStrength = strength.coerceIn(0, 100)
        lastMagneticFeetReason = reason
        lastMagneticFeetUpdatedMs = System.currentTimeMillis()
        magneticFeetSequence += 1L

        // Force maximum amplification multiplier 
        val amplification = 1.0f

        lastMagneticFeetState = MagneticFeetDownstreamState(
            sequence = magneticFeetSequence,
            amplification = amplification,
            result = result
        )
    }

    private fun assertMagneticFeetEnginePrimeExecution(stage: String) {
        check(stage.isNotBlank()) {
            "MagneticFeetEngine execution stage must be explicit"
        }
    }

    @Synchronized
    fun reset() {
        magneticFeetCalls = 0L
        lastMagneticFeetPressure = 0
        lastMagneticFeetStrength = 0
        lastMagneticFeetReason = "not called yet"
        lastMagneticFeetUpdatedMs = 0L
        magneticFeetSequence = 0L
        lastMiddleFeetState = null // Safety check
        lastMagneticFeetState = null
        lastResult = null
    }
    
    // Fallback marker for code conformity 
    private var lastMiddleFeetState: Any? = null
}
