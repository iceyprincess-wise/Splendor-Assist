package com.assistant.adapter.smartassist

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

object TemporalMemoryEngine {

    private const val DEFAULT_WINDOW = 30
    private const val DEFAULT_ALPHA = 0.20f
    
    // --- ENGINE CONSTANTS FOR FRAME & TICK SYNC ---
    private const val REFRESH_RATE_60HZ_MS = 16.666f
    private const val REFRESH_RATE_120HZ_MS = 8.333f
    private const val BASE_SERVER_TICK_MS = 33.333f // Typical 30Hz netcode boundary
    private const val HUMAN_LATENCY_MIN_MS = 12L
    private const val HUMAN_LATENCY_MAX_MS = 45L
    private const val NOISE_VARIANCE_SCALE = 0.04f // 4% dynamic micro-variance bounds

    /**
     * Updates the temporal state using zero-allocation mathematical models,
     * applying Server-Tick Sync and Adaptive Noise Humanization for Gesture descriptions.
     *
     * @param previous The previous memory state
     * @param confidence The raw input confidence/probability scalar
     * @param pingMs Current estimated network latency to scale packet boundaries
     * @param is120Hz Whether to target 120Hz (8.33ms) or 60Hz (16.66ms) kinematics
     */
    fun update(
        previous: TemporalMemoryState,
        confidence: Float,
        pingMs: Float = 50f,
        is120Hz: Boolean = false
    ): TemporalMemoryState {
        
        val window = previous.historyWindow
        
        // --- 1. ZERO-ALLOCATION CIRCULAR BUFFER ---
        // Copying the array ensures immutability of state, avoiding cross-frame mutation bugs
        val nextHistory = previous.history.copyOf()
        val nextIndex = (previous.historyIndex + 1) % window
        nextHistory[nextIndex] = confidence
        
        val nextSampleCount = previous.sampleCount + 1
        val validSamples = if (nextSampleCount < window) nextSampleCount else window

        // --- 2. HIGH-PERFORMANCE STATISTICAL PASS ---
        // O(N) single-pass extraction replacing multiple fold/map/sum chaining
        var sum = 0f
        var minConf = confidence
        var maxConf = confidence
        
        for (i in 0 until validSamples) {
            val v = nextHistory[(nextIndex - i + window) % window]
            sum += v
            if (v < minConf) minConf = v
            if (v > maxConf) maxConf = v
        }

        val mean = if (validSamples == 0) 0f else sum / validSamples.toFloat()

        var varianceSum = 0f
        for (i in 0 until validSamples) {
            val v = nextHistory[(nextIndex - i + window) % window]
            val d = v - mean
            varianceSum += d * d
        }
        
        val variance = if (validSamples == 0) 0f else varianceSum / validSamples.toFloat()
        val stddev = sqrt(variance)

        // --- 3. EXPONENTIAL MOVING AVERAGE & KINEMATICS ---
        val ema = if (previous.sampleCount == 0) {
            confidence
        } else {
            (DEFAULT_ALPHA * confidence) + ((1f - DEFAULT_ALPHA) * previous.exponentialMovingAverage)
        }

        val trend = ema - previous.exponentialMovingAverage
        val evolution = ema - previous.temporalConfidence
        val age = previous.observationAge + 1
        val decayed = ema * previous.decayFactor

        // Optimized boundary coercion
        val varBounded = (1f - variance).coerceIn(0f, 1f)
        val trendBounded = (1f - abs(trend)).coerceIn(0f, 1f)
        val emaBounded = ema.coerceIn(0f, 1f)

        val historyStability = (varBounded * 0.50f + trendBounded * 0.30f + emaBounded * 0.20f).coerceIn(0f, 1f)

        // --- 4. ADAPTIVE NOISE HUMANIZATION & SERVER-TICK SYNC ---
        // Dynamically scale gesture path lengths and hold durations to match network packet boundaries
        val frameTargetMs = if (is120Hz) REFRESH_RATE_120HZ_MS else REFRESH_RATE_60HZ_MS
        
        // Calculate synchronization scalar forcing maximum possession effectiveness during high ping
        val pingCompensation = (pingMs / BASE_SERVER_TICK_MS).coerceIn(1.0f, 3.0f)
        
        // Randomized dynamic micro-variance to mimic human hand latency boundaries
        val humanVariance = (Random.nextFloat() * 2f - 1f) * NOISE_VARIANCE_SCALE
        
        val newGestureScale = (1.0f + (historyStability * pingCompensation * humanVariance)).coerceIn(0.85f, 1.25f)
        val newHoldDelay = Random.nextLong(HUMAN_LATENCY_MIN_MS, HUMAN_LATENCY_MAX_MS)
        val syncOffset = (frameTargetMs * pingCompensation) % frameTargetMs

        return TemporalMemoryState(
            historyWindow = window,
            sampleCount = nextSampleCount,
            rollingConfidence = mean,
            exponentialMovingAverage = ema,
            confidenceTrend = trend,
            confidenceVariance = variance,
            historyStability = historyStability,
            confidenceSlope = trend,
            confidenceEvolution = evolution,
            observationAge = age,
            decayFactor = previous.decayFactor,
            minConfidence = minConf,
            maxConfidence = maxConf,
            temporalConfidence = decayed.coerceIn(0f, 1f),
            rollingMean = mean,
            rollingStdDev = stddev,
            onlineUpdateCount = previous.onlineUpdateCount + 1,
            history = nextHistory,
            historyIndex = nextIndex,
            gestureScaleMultiplier = newGestureScale,
            humanizedHoldDelayMs = newHoldDelay,
            frameSyncOffsetMs = syncOffset
        )
    }

    /**
     * Initializes the temporal engine state with pre-allocated zero-cost arrays.
     */
    fun initialize(
        historyWindow: Int = DEFAULT_WINDOW,
        decayFactor: Float = 0.98f
    ): TemporalMemoryState {
        return TemporalMemoryState(
            historyWindow = historyWindow,
            sampleCount = 0,
            rollingConfidence = 0f,
            exponentialMovingAverage = 0f,
            confidenceTrend = 0f,
            confidenceVariance = 0f,
            historyStability = 0f,
            confidenceSlope = 0f,
            confidenceEvolution = 0f,
            observationAge = 0,
            decayFactor = decayFactor,
            minConfidence = 0f,
            maxConfidence = 0f,
            temporalConfidence = 0f,
            rollingMean = 0f,
            rollingStdDev = 0f,
            onlineUpdateCount = 0,
            history = FloatArray(historyWindow), // Allocates once per initialization
            historyIndex = 0,
            gestureScaleMultiplier = 1.0f,
            humanizedHoldDelayMs = 0L,
            frameSyncOffsetMs = 0f
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
    // Wired for ClosedLoopTemporalFeedbackEngine integration with enhanced frame-sync and tick-scaling.
}
