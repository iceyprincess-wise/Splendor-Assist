package com.assistant

import kotlin.math.*
import kotlin.math.abs

object TemporalMemoryEngine {

    private const val DEFAULT_WINDOW = 30
    private const val DEFAULT_ALPHA = 0.20f
    
    // --- ENGINE CONSTANTS FOR FRAME & TICK SYNC ---

    /**
     * Updates the temporal state using zero-allocation mathematical models,
     * using zero-allocation history statistics (mean, variance, EMA, trend).
     *
     * @param previous The previous memory state
     * @param confidence The raw input confidence/probability scalar
     */
    fun update(
        previous: TemporalMemoryState,
        confidence: Float
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

        for (i in 0 until validSamples) {
            val v = nextHistory[(nextIndex - i + window) % window]
            sum += v
        }

        val mean = if (validSamples == 0) 0f else sum / validSamples.toFloat()

        var varianceSum = 0f
        for (i in 0 until validSamples) {
            val v = nextHistory[(nextIndex - i + window) % window]
            val d = v - mean
            varianceSum += d * d
        }

        val variance = if (validSamples == 0) 0f else varianceSum / validSamples.toFloat()

        // --- 3. EXPONENTIAL MOVING AVERAGE & KINEMATICS ---
        val ema = if (previous.sampleCount == 0) {
            confidence
        } else {
            (DEFAULT_ALPHA * confidence) + ((1f - DEFAULT_ALPHA) * previous.exponentialMovingAverage)
        }

        val trend = ema - previous.exponentialMovingAverage
        val decayed = ema * previous.decayFactor

        // Optimized boundary coercion
        val varBounded = (1f - variance).coerceIn(0f, 1f)
        val trendBounded = (1f - abs(trend)).coerceIn(0f, 1f)
        val emaBounded = ema.coerceIn(0f, 1f)

        val historyStability = (varBounded * 0.50f + trendBounded * 0.30f + emaBounded * 0.20f).coerceIn(0f, 1f)

        return TemporalMemoryState(
            historyWindow = window,
            sampleCount = nextSampleCount,
            exponentialMovingAverage = ema,
            confidenceTrend = trend,
            confidenceVariance = variance,
            historyStability = historyStability,
            confidenceSlope = trend,
            decayFactor = previous.decayFactor,
            temporalConfidence = decayed.coerceIn(0f, 1f),
            rollingMean = mean,
            history = nextHistory,
            historyIndex = nextIndex,
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
            exponentialMovingAverage = 0f,
            confidenceTrend = 0f,
            confidenceVariance = 0f,
            historyStability = 0f,
            confidenceSlope = 0f,
            decayFactor = decayFactor,
            temporalConfidence = 0f,
            rollingMean = 0f,
            history = FloatArray(historyWindow), // Allocates once per initialization
            historyIndex = 0,
        )
    }

    // PHASE8 CLOSED-LOOP TEMPORAL HOOK
}
