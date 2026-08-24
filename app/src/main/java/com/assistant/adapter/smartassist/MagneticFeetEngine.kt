package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.util.Log
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val MAGNETICFEETENGINE_PRIME_EXECUTION_TAG =
    "MagneticFeetEngine.prime"

data class MagneticFeetResult(
    val touchRetention: Float,
    val interceptionResistance: Float,
    val possessionControl: Float
)

object MagneticFeetEngine {

    // -------------------------------------------------------------------------
    // Diagnostics & downstream state
    // -------------------------------------------------------------------------
    data class MagneticFeetActivationDiagnostics(
        val calls: Long,
        val lastPressure: Int,
        val lastStrength: Int,
        val lastReason: String,
        val lastUpdatedMs: Long
    )

    data class MagneticFeetDownstreamState(
        val sequence: Long,
        /** 0‑1 range, reflects how “strong” the current stabilisation is */
        val amplification: Float,
        val result: MagneticFeetResult
    )

    // -------------------------------------------------------------------------
    // Input / output bounds – unchanged (public contract)
    // -------------------------------------------------------------------------
    private const val INPUT_MIN = 0
    private const val INPUT_MAX = 100

    private const val RESULT_MIN = 0.0f
    private const val RESULT_MAX = 10.0f

    // -------------------------------------------------------------------------
    // Internal bookkeeping – unchanged visibility
    // -------------------------------------------------------------------------
    private var magneticFeetCalls: Long = 0L
    private var lastMagneticFeetPressure: Int = 0
    private var lastMagneticFeetStrength: Int = 0
    private var lastMagneticFeetReason: String = "not called yet"
    private var lastMagneticFeetUpdatedMs: Long = 0L

    private var magneticFeetSequence: Long = 0L
    private var lastMagneticFeetState: MagneticFeetDownstreamState? = null

    /** Cache of the previous result – used for temporal smoothing */
    private var lastResult: MagneticFeetResult? = null

    // -------------------------------------------------------------------------
    // Public diagnostics
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Public entry‑points (unchanged signature)
    // -------------------------------------------------------------------------
    fun stabilize(
        service: AccessibilityService,
        currentX: Float,
        currentY: Float,
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        assertMagneticFeetEnginePrimeExecution("service-context stabilize")

        val coordinatesUsable =
            currentX.isFinite() && currentY.isFinite() && currentX >= 0f && currentY >= 0f

        val serviceReady = service.rootInActiveWindow != null

        val contextReason = when {
            !coordinatesUsable ->
                "service-context calculation; unusable coordinates"
            serviceReady ->
                "service-context calculation; accessibility context available"
            else ->
                "service-context calculation; accessibility context unavailable"
        }

        val result = calculate(pressure = pressure, strength = strength)

        publishInvocation(
            pressure = pressure,
            strength = strength,
            reason = contextReason,
            result = result
        )

        Log.d(
            MAGNETICFEETENGINE_PRIME_EXECUTION_TAG,
            "Service‑context calculation completed at (${currentX.toInt()},${currentY.toInt()}); " +
                "retention=${result.touchRetention}"
        )
        return result
    }

    fun stabilize(
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        assertMagneticFeetEnginePrimeExecution("controller stabilize")
        val result = calculate(pressure = pressure, strength = strength)

        publishInvocation(
            pressure = pressure,
            strength = strength,
            reason = "controller calculation",
            result = result
        )
        return result
    }

    // -------------------------------------------------------------------------
    // Core calculation – now uses non‑linear scaling + smoothing
    // -------------------------------------------------------------------------
    private fun calculate(
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {
        // Normalise inputs to 0‑1 range (clamped)
        val p = pressure.coerceIn(INPUT_MIN, INPUT_MAX) / 100.0f // defender density
        val s = strength.coerceIn(INPUT_MIN, INPUT_MAX) / 100.0f // lane confidence

        // -----------------------------------------------------------------
        // Signals
        // -----------------------------------------------------------------
        val synergy = p * s               // both high → pressure + viable lane
        val openness = 1.0f - p           // inverse pressure: room to manoeuvre

        // -----------------------------------------------------------------
        // Helper: exponential “feel‑good” curve.
        // f(x) = a * (1 - e^{-k·x})   → 0→0, 1→a
        // We use it to give a smoother ramp‑up for the three outputs.
        // -----------------------------------------------------------------
        fun expCurve(x: Float, a: Float, k: Float = 3.5f): Float =
            a * (1f - exp(-k * x))

        // ------------------- touchRetention -------------------------------
        // Base stickiness (1.0) + lane confidence (s) + openness boost
        // + a modest floor from pressure.
        val rawTouch = 1.0f +
                expCurve(s, 5.5f) +
                expCurve(openness * s, 2.5f) +
                (p * 0.5f)
        val touchRetention = rawTouch.coerceIn(RESULT_MIN, RESULT_MAX)

        // ------------------- interceptionResistance ------------------------
        // Stronger when a good lane exists *and* defenders are close.
        val rawInterception = 1.0f +
                expCurve(s, 4.0f) +
                expCurve(synergy, 4.0f) +
                (p * 0.5f)
        val interceptionResistance = rawInterception.coerceIn(RESULT_MIN, RESULT_MAX)

        // ------------------- possessionControl ----------------------------
        // Penalises “trapped” situations (high pressure, low lane confidence)
        // while rewarding clear‑lane, low‑pressure moments.
        val pressureRisk = p * (1f - s)
        val rawPossession = 1.0f +
                expCurve(s, 4.5f) +
                expCurve(openness, 2.5f) +
                expCurve(synergy, 1.5f) -
                (pressureRisk * 2.0f)
        val possessionControl = rawPossession.coerceIn(RESULT_MIN, RESULT_MAX)

        // -----------------------------------------------------------------
        // Temporal smoothing – 80 % new value, 20 % previous (if any)
        // -----------------------------------------------------------------
        val smoothed = lastResult?.let { prev ->
            MagneticFeetResult(
                touchRetention = 0.8f * touchRetention + 0.2f * prev.touchRetention,
                interceptionResistance = 0.8f * interceptionResistance + 0.2f * prev.interceptionResistance,
                possessionControl = 0.8f * possessionControl + 0.2f * prev.possessionControl
            )
        } ?: MagneticFeetResult(
            touchRetention = touchRetention,
            interceptionResistance = interceptionResistance,
            possessionControl = possessionControl
        )

        // Cache for the next call
        lastResult = smoothed

        return smoothed
    }

    // -------------------------------------------------------------------------
    // Publishing – now returns a *dynamic* amplification factor
    // -------------------------------------------------------------------------
    @Synchronized
    private fun publishInvocation(
        pressure: Int,
        strength: Int,
        reason: String,
        result: MagneticFeetResult
    ) {
        magneticFeetCalls += 1L
        lastMagneticFeetPressure = pressure.coerceIn(INPUT_MIN, INPUT_MAX)
        lastMagneticFeetStrength = strength.coerceIn(INPUT_MIN, INPUT_MAX)
        lastMagneticFeetReason = reason
        lastMagneticFeetUpdatedMs = System.currentTimeMillis()

        magneticFeetSequence += 1L

        // Dynamic amplification:
        //   - Starts at 0.4 (baseline) when pressure & strength are low.
        //   - Grows up to 1.0 when synergy (p * s) is high.
        // This makes the downstream snapshot immediately reflect *how
        // “important” the current stabilisation is* for a counter‑attack.
        val synergy = (lastMagneticFeetPressure / 100f) *
                (lastMagneticFeetStrength / 100f)
        val amplification = (0.4f + 0.6f * synergy).coerceIn(0f, 1f)

        lastMagneticFeetState = MagneticFeetDownstreamState(
            sequence = magneticFeetSequence,
            amplification = amplification,
            result = result
        )
    }

    // -------------------------------------------------------------------------
    // Guard helpers – unchanged
    // -------------------------------------------------------------------------
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
        lastResult = null
    }
}
