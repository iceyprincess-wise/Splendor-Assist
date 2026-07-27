package com.assistant.adapter.smartassist

import kotlin.math.max
import kotlin.random.Random

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
    private const val BASE_HUMAN_VARIANCE = 0.015f // 1.5% base dynamic spread

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
        networkPingMs: Float = 15.0f
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

        // 2. ADAPTIVE NOISE HUMANIZATION (Latency Boundary Masking)
        // Generates non-linear variance that tightens during high confidence
        // and loosens during low confidence to prevent robotic tracking detection.
        val rand = Random.Default
        val noiseFactor = (rand.nextFloat() * 2f) - 1f // [-1.0, 1.0] range
        val dynamicSpread = BASE_HUMAN_VARIANCE * (1.1f - clampedGain) 
        val humanizedGain = fastClamp(clampedGain + (noiseFactor * dynamicSpread), 0f, 1f)

        // 3. SERVER-TICK SYNC (High-Ping Netcode Compensation)
        // Scales the hold durations and physical coordinate mappings to perfectly
        // align with the game's server authoritative packet consumption limits.
        val frameTimeMs = if (targetRefreshRateHz >= 120) REFRESH_120HZ_MS else REFRESH_60HZ_MS
        val packetSyncRatio = SERVER_TICK_BOUNDARY_MS / max(frameTimeMs, 1.0f)
        
        // Ping compensation multiplier ensures inputs stretch across packet loss windows
        val pingMultiplier = fastClamp(networkPingMs / SERVER_TICK_BOUNDARY_MS, 1.0f, 3.0f)
        val finalTickMultiplier = packetSyncRatio * pingMultiplier

        // PHASE 8 CLOSED-LOOP TEMPORAL HOOK
        // Output fully wired for downstream ClosedLoopTemporalFeedbackEngine integration
        return OnlineParameterAdaptationResult(
            confidence = state.confidence,
            adaptationGain = humanizedGain,
            tickSyncMultiplier = finalTickMultiplier,
            humanizedNoise = (noiseFactor * dynamicSpread)
        )
    }

    /**
     * Inline, zero-allocation float clamping to maximize coordinate translation speed.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastClamp(value: Float, minVal: Float, maxVal: Float): Float {
        return if (value < minVal) minVal else if (value > maxVal) maxVal else value
    }
}
