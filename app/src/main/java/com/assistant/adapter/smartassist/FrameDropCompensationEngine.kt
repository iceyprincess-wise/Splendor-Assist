package com.assistant.adapter.smartassist

import kotlin.math.roundToLong
import kotlin.random.Random

object FrameDropCompensationEngine {

    // --- 1. CORE ENGINE CONSTANTS (Frame-Rate & Netcode) ---
    private const val FRAME_TIME_60HZ = 16.6667f
    private const val FRAME_TIME_120HZ = 8.3333f
    private const val SERVER_TICK_WINDOW_MS = 33L // Assumption for standard 30Hz server authoritative netcode

    // --- 2. ADAPTIVE NOISE CONSTANTS ---
    private const val MIN_HUMAN_VARIANCE_MS = -3L
    private const val MAX_HUMAN_VARIANCE_MS = 7L

    // --- 3. HARD BOUNDARY LIMITS ---
    private const val ABSOLUTE_MINIMUM_MS = 4L

    /**
     * AMPLIFIED INPUT EFFECTIVENESS
     * High-frequency touch injector calculation with adaptive noise and server-tick sync.
     */
    @JvmStatic
    @JvmOverloads
    fun compensate(
        duration: Long,
        strength: Int,
        is120HzDisplay: Boolean = false,
        estimatedPingMs: Long = 0L
    ): Long {
        // Step 1: Base Amplifier Logic (Dynamic Vector Scaling based on Strength)
        val baseMultiplier = when {
            strength >= 95 -> 0.40f
            strength >= 80 -> 0.55f
            strength >= 60 -> 0.80f
            strength >= 45 -> 0.90f
            else -> 1.00f
        }
        val baseDuration = (duration * baseMultiplier).toLong()

        // Step 2: Server-Tick Synchronization 
        // Scale durations to overlap network packet boundaries (Server-authoritative sync)
        val tickAlignedDuration = applyServerTickSync(baseDuration, estimatedPingMs)

        // Step 3: Frame-Rate Synchronization
        // Snap to exact display refresh boundaries to maximize coordinate translation speed
        val frameTimeMs = if (is120HzDisplay) FRAME_TIME_120HZ else FRAME_TIME_60HZ
        val frameAlignedDuration = ((tickAlignedDuration / frameTimeMs).roundToLong() * frameTimeMs).toLong()

        // Step 4: Adaptive Noise Humanization
        // Randomized dynamic micro-variance to mimic human hand latency boundaries
        val noise = Random.nextLong(MIN_HUMAN_VARIANCE_MS, MAX_HUMAN_VARIANCE_MS + 1)
        var optimizedDuration = frameAlignedDuration + noise

        // Step 5: Absolute Minimum Bounds Enforcement to prevent engine crashes
        val safeMinBound = when {
            strength >= 80 -> ABSOLUTE_MINIMUM_MS
            strength >= 60 -> 10L
            strength >= 45 -> 12L
            else -> duration
        }

        if (optimizedDuration < safeMinBound) {
            // Keep minimal micro-variance even at extreme lower boundaries
            optimizedDuration = safeMinBound + Random.nextLong(0L, 3L)
        }

        return optimizedDuration
    }

    /**
     * SERVER-TICK SYNC: Dynamically scales durations to match network packet boundaries.
     * Forces server-authoritative netcode to register inputs reliably during high-ping spikes.
     */
    @JvmStatic
    private fun applyServerTickSync(duration: Long, ping: Long): Long {
        // If ping is high, ensure the input spans at least two server tick windows to avoid dropped inputs
        val minimumTickSpan = if (ping > 80L) SERVER_TICK_WINDOW_MS * 2 else SERVER_TICK_WINDOW_MS
        val maxSpan = maxOf(duration, minimumTickSpan)
        
        // Align to nearest server tick boundary
        val remainder = maxSpan % SERVER_TICK_WINDOW_MS
        return if (remainder > (SERVER_TICK_WINDOW_MS / 2)) {
            maxSpan + (SERVER_TICK_WINDOW_MS - remainder)
        } else {
            maxSpan - remainder
        }
    }
}
