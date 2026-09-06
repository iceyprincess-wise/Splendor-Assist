package com.assistant.adapter.smartassist

import java.util.concurrent.atomic.AtomicLong

/**
 * Core Magnetic Feet signal engine.
 *
 * The previous implementation contained an unimplemented native-memory
 * pointer backend and an orphan MAX_PRIORITY polling thread that was not
 * part of the live runtime decision path.
 *
 * The live architecture already provides real ball telemetry, player
 * tracking and possession state. This engine therefore stays pure and
 * deterministic: it converts bounded runtime signals into immutable
 * specialist outputs consumed by the contributor and diagnostics.
 */
object MagneticFeetEngine {

    private const val MAX_SIGNAL = 100.0f
    private const val MAX_TOUCH = 15.0f
    private const val MAX_INTERCEPTION = 12.0f
    private const val MAX_POSSESSION = 12.0f
    private const val MAX_AMPLIFICATION = 1.2f

    private val calls = AtomicLong(0L)

    @Volatile
    private var lastPressure = 0

    @Volatile
    private var lastStrength = 0

    @Volatile
    private var lastReason = "none"

    @Volatile
    private var lastUpdatedMs = 0L

    @Volatile
    private var lastAmplification = 1.0f

    @Volatile
    private var lastResult = MagneticFeetResult()

    data class MagneticFeetResult(
        val touchRetention: Float = 0.0f,
        val interceptionResistance: Float = 0.0f,
        val possessionControl: Float = 0.0f
    )

    data class MagneticFeetState(
        val sequence: Long = 0L,
        val amplification: Float = 1.0f,
        val result: MagneticFeetResult = MagneticFeetResult()
    )

    data class MagneticFeetDiagnostics(
        val calls: Long = 0L,
        val lastPressure: Int = 0,
        val lastStrength: Int = 0,
        val lastReason: String = "none",
        val lastUpdatedMs: Long = 0L
    )

    /**
     * Converts bounded 0..100 pressure and strength signals into
     * continuously varying specialist outputs.
     *
     * The old implementation began at 15.0 and then added only positive
     * terms before clamping to 15, so touchRetention was permanently 15.
     * This version deliberately keeps the signal below the final ceiling
     * so the inputs remain observable in the result.
     */
    fun stabilize(
        pressure: Int,
        strength: Int
    ): MagneticFeetResult {

        val safePressure =
            pressure.coerceIn(0, 100)

        val safeStrength =
            strength.coerceIn(0, 100)

        calls.incrementAndGet()

        lastPressure = safePressure
        lastStrength = safeStrength
        lastReason = "stabilized"
        lastUpdatedMs = System.currentTimeMillis()

        val pressureNorm =
            safePressure / MAX_SIGNAL

        val strengthNorm =
            safeStrength / MAX_SIGNAL

        val synergy =
            (pressureNorm * strengthNorm)
                .coerceIn(0.0f, 1.0f)

        /*
         * Real bounded amplification:
         *
         * weak signals  -> 1.0
         * strong signals -> 1.2
         *
         * This replaces the old 1.2 + dummySynergy expression whose base
         * value already sat at the upper clamp.
         */
        val amplification =
            (
                1.0f +
                    (0.2f * synergy)
            ).coerceIn(
                1.0f,
                MAX_AMPLIFICATION
            )

        /*
         * Continuous touch scale.
         *
         * At 0/0 this starts at 2.
         * At 100/100 it reaches 10 before amplification.
         * This prevents the previous guaranteed 15.0 saturation.
         */
        val calculatedTouch =
            (
                2.0f +
                    (safePressure * 0.04f) +
                    (safeStrength * 0.04f)
            ).coerceIn(
                0.0f,
                10.0f
            )

        val result =
            MagneticFeetResult(
                touchRetention =
                    (
                        calculatedTouch *
                            amplification
                    ).coerceIn(
                        0.0f,
                        MAX_TOUCH
                    ),

                interceptionResistance =
                    (
                        safePressure *
                            0.12f
                    ).coerceIn(
                        0.0f,
                        MAX_INTERCEPTION
                    ),

                possessionControl =
                    (
                        safeStrength *
                            0.12f
                    ).coerceIn(
                        0.0f,
                        MAX_POSSESSION
                    )
            )

        lastAmplification =
            amplification

        lastResult =
            result

        return result
    }

    fun reset() {
        calls.set(0L)
        lastPressure = 0
        lastStrength = 0
        lastReason = "none"
        lastUpdatedMs = 0L
        lastAmplification = 1.0f
        lastResult = MagneticFeetResult()
    }

    /**
     * Read-only diagnostic snapshot.
     *
     * IMPORTANT:
     * This function does not call stabilize(). Reading diagnostics must
     * never mutate runtime counters or create synthetic engine activity.
     */
    fun magneticFeetSnapshot():
        MagneticFeetState? {

        return MagneticFeetState(
            sequence = calls.get(),
            amplification = lastAmplification,
            result = lastResult
        )
    }

    fun magneticFeetActivationDiagnostics():
        MagneticFeetDiagnostics {

        return MagneticFeetDiagnostics(
            calls = calls.get(),
            lastPressure = lastPressure,
            lastStrength = lastStrength,
            lastReason = lastReason,
            lastUpdatedMs = lastUpdatedMs
        )
    }
}
