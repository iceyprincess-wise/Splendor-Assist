package com.assistant.adapter.smartassist

import kotlin.random.Random
import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

class ZeroFramePressEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    private companion object {
        // Optimized math: distance calculation via squared values prevents costly Math.sqrt operations
        const val ENGAGEMENT_DISTANCE_SQR = 45.0f * 45.0f

        // Server-Tick Sync: Network packet boundary constants for high-ping resilience
        const val BASE_HOLD_DURATION_MS = 25L
        const val TICK_RATE_MULTIPLIER = 1.25f
        
        // Adaptive Noise bounds
        const val NOISE_VARIANCE_MIN = -1.25f
        const val NOISE_VARIANCE_MAX = 1.25f
        const val PATH_SCALAR_BASE = 5.0f
    }

    /**
     * High-performance execution block for instant touch injection.
     * Operates purely with local stack variables to guarantee zero heap allocation.
     */
    fun executeInstantSteal(
        defX: Float,
        defY: Float,
        ballX: Float,
        ballY: Float,
        dashButtonX: Float,
        dashButtonY: Float
    ) {
        // Direct stack calculations, bypassing the old FloatArray heap state buffer
        val deltaX = ballX - defX
        val deltaY = ballY - defY

        // Fast euclidean distance squared
        val distanceSqr = (deltaX * deltaX) + (deltaY * deltaY)

        if (distanceSqr < ENGAGEMENT_DISTANCE_SQR) {
            
            // Adaptive Noise Humanization: Dynamic micro-variance mimicking human hand latency boundaries
            val microVarianceX = NOISE_VARIANCE_MIN + (Random.nextFloat() * (NOISE_VARIANCE_MAX - NOISE_VARIANCE_MIN))
            val microVarianceY = NOISE_VARIANCE_MIN + (Random.nextFloat() * (NOISE_VARIANCE_MAX - NOISE_VARIANCE_MIN))

            // Server-Tick Sync: Dynamically scale gesture path lengths and hold durations to match packet boundaries
            val dynamicPathLength = (PATH_SCALAR_BASE * TICK_RATE_MULTIPLIER) + (Random.nextFloat() * 1.5f)
            
            // Modulating hold duration to register maximum possession effectiveness
            val randomHoldVariance = Random.nextLong(3L, 9L)
            val dynamicHoldDuration = (BASE_HOLD_DURATION_MS * TICK_RATE_MULTIPLIER).toLong() + randomHoldVariance

            val originX = dashButtonX + microVarianceX
            val originY = dashButtonY + microVarianceY

            val targetX = originX + dynamicPathLength
            val targetY = originY + (microVarianceY * 0.5f) // Subtle angular trajectory variance

            inputEngine.injectZeroLatencySwipe(
                originX,
                originY,
                targetX,
                targetY,
                dynamicHoldDuration
            )
        }
    }
}
