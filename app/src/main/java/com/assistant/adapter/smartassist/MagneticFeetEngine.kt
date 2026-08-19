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
        // Inputs: integer [0,100] → normalised [0,1]. Integer coercion
        // before division guarantees no NaN or Infinity from the arithmetic.
        val p = pressure.coerceIn(INPUT_MIN, INPUT_MAX) / 100.0f   // defender density
        val s = strength.coerceIn(INPUT_MIN, INPUT_MAX) / 100.0f   // lane confidence

        // Derived signals — all bounded by arithmetic on [0,1] inputs.
        val synergy  = p * s          // both high: pressure + viable lane co-occur
        val openness = 1.0f - p       // inverse pressure: room to manoeuvre

        // ── touchRetention ──────────────────────────────────────────────
        // Semantic: ball-control stickiness and support stability.
        // Driven primarily by lane confidence (s): a clear lane means the
        // player can settle the ball with intent. Openness amplifies s
        // (room to exploit the lane). Raw pressure contributes a small
        // floor (urgency in close contact still demands some retention).
        val touchRetention = (
            1.0f +
                (s       * 5.5f) +
                (openness * s * 2.5f) +
                (p       * 0.5f)
        ).coerceIn(RESULT_MIN, RESULT_MAX)

        // ── interceptionResistance ───────────────────────────────────────
        // Semantic: ability to hold the ball as defenders close in.
        // Peaks when BOTH a viable escape lane exists AND real defensive
        // pressure is applied. A great lane with no pressure scores
        // moderately (5.0); trapped with no lane scores near baseline (1.5).
        val interceptionResistance = (
            1.0f +
                (s       * 4.0f) +
                (synergy * 4.0f) +
                (p       * 0.5f)
        ).coerceIn(RESULT_MIN, RESULT_MAX)

        // ── possessionControl ────────────────────────────────────────────
        // Semantic: contextual confidence that maintaining ball-control is
        // appropriate. Penalised when trapped: high pressure with no escape
        // lane produces a pressureRisk signal that subtracts from the score.
        // Ensures MagneticFeet self-depresses its confidence when the
        // tactical situation does not support holding.
        val pressureRisk = p * (1.0f - s)
        val possessionControl = (
            1.0f +
                (s       * 4.5f) +
                (openness * 2.5f) +
                (synergy * 1.5f) -
                (pressureRisk * 2.0f)
        ).coerceIn(RESULT_MIN, RESULT_MAX)

        return MagneticFeetResult(
            touchRetention        = touchRetention,
            interceptionResistance = interceptionResistance,
            possessionControl     = possessionControl
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

        // Amplification: live diagnostic of average engine output strength,
        // normalised to [0,1]. Replaces the former static 1.0f constant
        // which was stored in the snapshot but never consumed by any calculation.
        val amplification = (
            result.touchRetention +
                result.interceptionResistance +
                result.possessionControl
        ) / (3.0f * RESULT_MAX)

        lastMagneticFeetState =
            MagneticFeetDownstreamState(
                sequence      = magneticFeetSequence,
                amplification = amplification,
                result        = result
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
