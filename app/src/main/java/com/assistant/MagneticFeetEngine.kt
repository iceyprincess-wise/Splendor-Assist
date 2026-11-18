package com.assistant






































import java.util.concurrent.atomic.AtomicLong

import kotlin.math.*




























object MagneticFeetEngine {

    private const val MAX_SIGNAL = 100.0f
    private const val MAX_TOUCH = 12.0f
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

    // SPLENDOR_P10_TRAP_DIAG_FIELD: last trap window seen by contribute().
    @Volatile
    private var lastTrapWindow = "none"

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

    // SPLENDOR_P10_TRAP_DIAG_CLASS: lastTrapWindow surfaced here.
    data class MagneticFeetDiagnostics(
        val calls: Long = 0L,
        val lastPressure: Int = 0,
        val lastStrength: Int = 0,
        val lastReason: String = "none",
        val lastTrapWindow: String = "none",
        val lastUpdatedMs: Long = 0L
    )

    /**
     * Converts bounded 0..100 proximity and strength signals into
     * continuously varying specialist outputs.
     *
     * The old implementation began at 15.0 and then added only positive
     * terms before clamping to 15, so touchRetention was permanently 15.
     * This version deliberately keeps the signal below the final ceiling
     * so the inputs remain observable in the result.
     */
    @Synchronized
    fun stabilize(
        proximity: Int,
        strength: Int
    ): MagneticFeetResult {

        val safeProximity =
            proximity.coerceIn(0, 100)

        val safeStrength =
            strength.coerceIn(0, 100)

        calls.incrementAndGet()

        lastPressure = safeProximity
        lastStrength = safeStrength
        lastReason = "stabilized p=$safeProximity s=$safeStrength"
        lastUpdatedMs = System.currentTimeMillis()

        val proximityNorm =
            safeProximity / MAX_SIGNAL

        val strengthNorm =
            safeStrength / MAX_SIGNAL

        val synergy =
            (proximityNorm * strengthNorm)
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
         * No68-B-D34: at 0/0 this starts at 0 -- the old 2.0 baseline
         * paid authority for zero signal (stabilize(0,0) returned a
         * touch of 2.0, which the contributor turned into 0.57
         * authority), the same fabrication class as the 0.9 floor the
         * P9 round removed. At 100/100 it reaches 10 before
         * amplification (0.05 coefficients), so the 12.0 ceiling is
         * reachable exactly at full signal instead of unreachable.
         */
        val calculatedTouch =
            (
                0.0f +
                    (safeProximity * 0.05f) +
                    (safeStrength * 0.05f)
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
                        safeProximity *
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

    // SPLENDOR_P10_TRAP_DIAG_RECORD: records the trap window computed by
    // MagneticFeetContributor.contribute(). Read-only for diagnostics;
    // it never feeds back into stabilize(). No68-B-D43: only the three
    // windows contribute() can produce are stored; anything else is a
    // caller bug and must not pollute the diagnostic.
    @Synchronized
    fun recordTrapWindow(window: String) {
        if (window == "CLOSING" || window == "SETTLED" || window == "LEAVING") {
            lastTrapWindow = window
        }
    }

    @Synchronized
    fun reset() {
        calls.set(0L)
        lastPressure = 0
        lastStrength = 0
        lastReason = "none"
        lastTrapWindow = "none"
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
     *
     * No68-B-D41: the return is non-null -- the old nullable type could
     * never return null and forced defensive `?.` on every caller.
     */
    @Synchronized
    fun magneticFeetSnapshot():
        MagneticFeetState {

        return MagneticFeetState(
            sequence = calls.get(),
            amplification = lastAmplification,
            result = lastResult
        )
    }

    @Synchronized
    fun magneticFeetActivationDiagnostics():
        MagneticFeetDiagnostics {

        return MagneticFeetDiagnostics(
            calls = calls.get(),
            lastPressure = lastPressure,
            lastStrength = lastStrength,
            lastReason = lastReason,
            // SPLENDOR_P10_TRAP_DIAG_SNAPSHOT
            lastTrapWindow = lastTrapWindow,
            lastUpdatedMs = lastUpdatedMs
        )
    }
}
