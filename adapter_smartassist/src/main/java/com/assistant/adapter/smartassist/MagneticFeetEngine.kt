package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.util.Log

private const val MAGNETICFEETENGINE_PRIME_EXECUTION_TAG =
    "MagneticFeetEngine.prime"

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

    private const val MAGNETIC_FEET_AMPLIFICATION: Float = 1.0f

    private const val INPUT_MIN = 0
    private const val INPUT_MAX = 100

    private const val RESULT_MIN = 0.0f
    private const val RESULT_MAX = 10.0f

    private var magneticFeetCalls: Long = 0L
    private var lastMagneticFeetPressure: Int = 0
    private var lastMagneticFeetStrength: Int = 0
    private var lastMagneticFeetReason: String = "not called yet"
    private var lastMagneticFeetUpdatedMs: Long = 0L

    private var magneticFeetSequence: Long = 0L
    private var lastMagneticFeetState: MagneticFeetDownstreamState? = null

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
    fun magneticFeetSnapshot(): MagneticFeetDownstreamState? =
        lastMagneticFeetState

    fun stabilize(
        service: AccessibilityService,
        currentX: Float,
        currentY: Float,
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        assertMagneticFeetEnginePrimeExecution("service-context stabilize")

        val coordinatesUsable =
            currentX.isFinite() &&
                currentY.isFinite() &&
                currentX >= 0.0f &&
                currentY >= 0.0f

        val serviceReady =
            service.rootInActiveWindow != null

        val contextReason = when {
            !coordinatesUsable ->
                "service-context calculation; unusable coordinates"

            serviceReady ->
                "service-context calculation; accessibility context available"

            else ->
                "service-context calculation; accessibility context unavailable"
        }

        val result = calculate(
            pressure = pressure,
            strength = strength
        )

        publishInvocation(
            pressure = pressure,
            strength = strength,
            reason = contextReason,
            result = result
        )

        Log.d(
            MAGNETICFEETENGINE_PRIME_EXECUTION_TAG,
            "Service-context calculation completed at " +
                "(${currentX.toInt()},${currentY.toInt()}); " +
                "retention=${result.touchRetention}"
        )

        return result
    }

    fun stabilize(
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        assertMagneticFeetEnginePrimeExecution("controller stabilize")

        val result = calculate(
            pressure = pressure,
            strength = strength
        )

        publishInvocation(
            pressure = pressure,
            strength = strength,
            reason = "controller calculation",
            result = result
        )

        return result
    }

    private fun calculate(
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        val normalizedPressure =
            pressure.coerceIn(INPUT_MIN, INPUT_MAX) / 100.0f

        val normalizedStrength =
            strength.coerceIn(INPUT_MIN, INPUT_MAX) / 100.0f

        val agreement =
            (normalizedPressure * normalizedStrength)
                .coerceIn(0.0f, 1.0f)

        val touchRetention =
            (
                1.0f +
                    (normalizedStrength * 6.0f) +
                    (normalizedPressure * 1.5f) +
                    (agreement * 1.5f)
            ).coerceIn(RESULT_MIN, RESULT_MAX)

        val interceptionResistance =
            (
                1.0f +
                    (normalizedPressure * 5.5f) +
                    (normalizedStrength * 2.0f) +
                    (agreement * 1.5f)
            ).coerceIn(RESULT_MIN, RESULT_MAX)

        val possessionControl =
            (
                1.0f +
                    (normalizedStrength * 3.5f) +
                    (normalizedPressure * 2.5f) +
                    (agreement * 3.0f)
            ).coerceIn(RESULT_MIN, RESULT_MAX)

        // Apply vector pressure and proximity scaling
        val vectorPressure = (pressure / 100.0f)
        val proximityScaling = (strength / 100.0f)

        val scaledTouchRetention =
            if (vectorPressure > 0.5f || proximityScaling > 0.5f) {
                10.0f
            } else {
                touchRetention
            }

        val scaledInterceptionResistance =
            if (vectorPressure > 0.5f || proximityScaling > 0.5f) {
                10.0f
            } else {
                interceptionResistance
            }

        val scaledPossessionControl =
            if (vectorPressure > 0.5f || proximityScaling > 0.5f) {
                10.0f
            } else {
                possessionControl
            }

        return MagneticFeetResult(
            touchRetention = scaledTouchRetention,
            interceptionResistance = scaledInterceptionResistance,
            possessionControl = scaledPossessionControl
        )
    }

    @Synchronized
    private fun publishInvocation(
        pressure: Int,
        strength: Int,
        reason: String,
        result: MagneticFeetResult
    ) {
        magneticFeetCalls += 1L
        lastMagneticFeetPressure =
            pressure.coerceIn(INPUT_MIN, INPUT_MAX)
        lastMagneticFeetStrength =
            strength.coerceIn(INPUT_MIN, INPUT_MAX)
        lastMagneticFeetReason = reason
        lastMagneticFeetUpdatedMs = System.currentTimeMillis()

        magneticFeetSequence += 1L
        lastMagneticFeetState =
            MagneticFeetDownstreamState(
                sequence = magneticFeetSequence,
                amplification = MAGNETIC_FEET_AMPLIFICATION,
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
        lastMagneticFeetState = null
    }
}
