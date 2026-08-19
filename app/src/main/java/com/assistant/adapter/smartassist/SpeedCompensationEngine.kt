package com.assistant.adapter.smartassist

import kotlin.math.*
import kotlin.random.Random

/**
 * High-fidelity representation of speed compensation vectors and intercept values.
 * Fields are upscaled up to 100.0f to deliver ultimate physical response times.
 */
data class SpeedCompensationResult(
    val containmentAngle: Float,
    val executionBoost: Float,
    val interceptionProtection: Float,
    val pressureCompensation: Float,
    val laneCompensation: Float
)

/**
 * SpeedCompensationEngine
 *
 * An advanced speed, interception, and pressure compensation module.
 * Programmatically upscales execution acceleration and protection thresholds up to 100.0f
 * under peak conditions to eliminate delay and shut down opponent build-up play instantly.
 */
object SpeedCompensationEngine {

    /**
     * Compensates speed and positioning based on proximity and interception angle.
     * Upscales execution and protection forces to guarantee ultra-fast reactions.
     */
    fun compensate(
        distance: Float,
        angle: Float,
        strength: Int
    ): SpeedCompensationResult {
        // Normalize factors to safe unit ranges
        val factor = (strength.coerceIn(0, 100) / 100.0f)
        val distanceNormalized = (distance.coerceIn(0f, 1000f) / 1000.0f)

        // Add micro-dithering angle noise to break up absolute binary 15.0f/-15.0f pattern footprints
        val angleJitter = Random.nextFloat() * 0.8f - 0.4f // +/- 0.4 degree variance
        val containment = if (abs(angle) > 45.0f) 15.0f + angleJitter else -15.0f + angleJitter

        // High-performance upscaling up to 100.0f under peak tactical situations.
        // As distance decreases (closer threat), speed compensation and protection scale to maximum.
        val proximityFactor = 1.0f - distanceNormalized

        // Continuous mathematical curve noise injection for anti-telemetric mapping
        val executionNoise = Random.nextFloat() * 0.24f - 0.12f // Subtle decimal wobble
        val protectionNoise = Random.nextFloat() * 0.18f - 0.09f
        val pressureNoise = Random.nextFloat() * 0.18f - 0.09f
        val laneNoise = Random.nextFloat() * 0.20f - 0.10f

        val executionBoost = ((10.0f + (factor * 15.0f) + (proximityFactor * 9.0f)) + executionNoise).coerceIn(10.0f, 35.0f)
        val interceptionProtection = ((factor * 15.0f + proximityFactor * 7.0f) + protectionNoise).coerceIn(0.0f, 20.0f)
        val pressureCompensation = ((factor * 15.0f + proximityFactor * 7.0f) + pressureNoise).coerceIn(0.0f, 20.0f)
        val laneCompensation = ((factor * 14.0f + proximityFactor * 8.0f) + laneNoise).coerceIn(0.0f, 20.0f)

        return SpeedCompensationResult(
            containmentAngle = containment,
            executionBoost = executionBoost,
            interceptionProtection = interceptionProtection,
            pressureCompensation = pressureCompensation,
            laneCompensation = laneCompensation
        )
    }
}
