package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*







import kotlin.math.max




















object OnlineParameterAdaptationEngine {

    // =========================================================================
    // CORE MATHEMATICAL WEIGHT CONSTANTS (Optimized for zero-allocation math)
    // =========================================================================
    private const val W_CALIBRATION = 0.35f
    private const val W_STATE_CONF = 0.15f
    private const val W_FIELD_CONF = 0.10f
    private const val W_EMA = 0.15f
    private const val W_ROLLING_MEAN = 0.10f
    private const val W_TEMPORAL_CONF = 0.10f
    private const val W_VARIANCE = 0.05f

    // =========================================================================
    // ENGINE PARAMETERS: TICK SYNC & ADAPTIVE NOISE HUMANIZATION
    // =========================================================================
    private const val REFRESH_60HZ_MS = 16.6667f
    private const val REFRESH_120HZ_MS = 8.3333f
    private const val SERVER_TICK_BOUNDARY_MS = 33.333f // Typical 30Hz server tick

    // Queue item 8: the ping default, named. VisionCore feeds the measured
    // RTT through effectivePingMs(); this is only the fallback for "never
    // measured yet" (NetProbeEngine.rtt == 0f).
    const val DEFAULT_PING_MS = 15.0f

    // Queue item 8: baseline tick-sync multiplier on a perfect link —
    // packetSyncRatio at the 60 Hz default (SERVER_TICK_BOUNDARY_MS /
    // REFRESH_60HZ_MS = 33.333 / 16.667 = 2.0) times pingMultiplier 1.0
    // (ping <= one server tick). The tick-sync consumer divides by this so
    // a good link stretches nothing and only the ping leg stretches holds.
    const val BASELINE_TICK_SYNC_MULTIPLIER = 2.0f

    /**
     * Queue item 8 (Gap 2): the measured-ping feed, as a pure function.
     * NetProbeEngine.rtt is the EMA-smoothed TCP-connect RTT; 0f means
     * never measured yet (or just wiped by onLinkChanged) and NaN /
     * negative can never be a real measurement — all three fall back to
     * the default instead of feeding a fabricated 0 ms ping.
     */
    @JvmStatic
    fun effectivePingMs(measuredRttMs: Float): Float =
        if (measuredRttMs.isFinite() && measuredRttMs > 0f) measuredRttMs else DEFAULT_PING_MS

    /**
     * Queue item 8 (Gap 3): the tick-sync hold stretch, as a pure function.
     * The packetSyncRatio leg of tickSyncMultiplier is frame geometry
     * ("2 client frames fit in one server tick"), not a stretch — only the
     * ping leg stretches holds. Dividing by the baseline leaves exactly
     * the ping leg: 1.0 at good ping (zero behaviour change at baseline),
     * rising to 3.0 at ping >= 100 ms (the ping clamp's own bound carried
     * through). Non-finite or non-positive input stretches nothing.
     */
    @JvmStatic
    fun tickSyncStretch(tickSyncMultiplier: Float): Float =
        if (tickSyncMultiplier.isFinite() && tickSyncMultiplier > 0f)
            (tickSyncMultiplier / BASELINE_TICK_SYNC_MULTIPLIER).coerceIn(1f, 3f)
        else 1f

    /**
     * Amplified Input Effectiveness analysis integrating closed-loop temporal feedback,
     * adaptive humanization, and authoritative server-tick synchronization.
     */
    @JvmStatic
    @JvmOverloads
    fun analyze(
        calibration: RuntimeConfidenceCalibrationResult,
        state: GameStateSnapshot,
        temporal: TemporalMemoryState,
        targetRefreshRateHz: Int = 60,
        networkPingMs: Float = DEFAULT_PING_MS
    ): OnlineParameterAdaptationResult {
        
        // 1. FAST-PATH COORDINATE & CONFIDENCE TRANSLATION
        val invVariance = fastClamp(1f - temporal.confidenceVariance, 0f, 1f)
        
        val baseGain = (
            calibration.calibratedConfidence * W_CALIBRATION +
            state.confidence * W_STATE_CONF +
            state.fieldConfidence * W_FIELD_CONF +
            temporal.exponentialMovingAverage * W_EMA +
            temporal.rollingMean * W_ROLLING_MEAN +
            temporal.temporalConfidence * W_TEMPORAL_CONF +
            invVariance * W_VARIANCE
        )

        val clampedGain = fastClamp(baseGain, 0f, 1f)

        // 2. No fabricated noise — clamped gain is the real adaptation signal
        val humanizedGain = clampedGain

        // 3. SERVER-TICK SYNC (High-Ping Netcode Compensation)
        // Scales the hold durations and physical coordinate mappings to perfectly
        // align with the game's server authoritative packet consumption limits.
        val frameTimeMs = if (targetRefreshRateHz >= 120) REFRESH_120HZ_MS else REFRESH_60HZ_MS
        val packetSyncRatio = SERVER_TICK_BOUNDARY_MS / max(frameTimeMs, 1.0f)
        
        // Ping compensation multiplier ensures inputs stretch across packet loss windows
        val pingMultiplier = fastClamp(networkPingMs / SERVER_TICK_BOUNDARY_MS, 1.0f, 3.0f)
        val finalTickMultiplier = packetSyncRatio * pingMultiplier

        // PHASE 8 CLOSED-LOOP TEMPORAL HOOK
        return OnlineParameterAdaptationResult(
            confidence = state.confidence,
            adaptationGain = humanizedGain,
            tickSyncMultiplier = finalTickMultiplier,
        )
    }

    /**
     * Inline, zero-allocation float clamping to maximize coordinate translation speed.
     */
    private fun fastClamp(value: Float, minVal: Float, maxVal: Float): Float {
        return if (value < minVal) minVal else if (value > maxVal) maxVal else value
    }
}

/* ========
OnlineParameterAdaptationResult
======== */
