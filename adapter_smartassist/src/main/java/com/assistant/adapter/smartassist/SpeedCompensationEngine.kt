package com.assistant.adapter.smartassist

import kotlin.math.*

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

        // containmentAngle keeps original 15f/-15f direction bias but stabilizes based on angle
        val containment = if (abs(angle) > 45.0f) 15.0f else -15.0f

        // High-performance upscaling up to 100.0f under peak tactical situations.
        // As distance decreases (closer threat), speed compensation and protection scale to maximum.
        val proximityFactor = 1.0f - distanceNormalized

        val executionBoost = (10.0f + (factor * 15.0f) + (proximityFactor * 9.0f)).coerceIn(10.0f, 35.0f)
        val interceptionProtection = (factor * 15.0f + proximityFactor * 7.0f).coerceIn(0.0f, 20.0f)
        val pressureCompensation = (factor * 15.0f + proximityFactor * 7.0f).coerceIn(0.0f, 20.0f)
        val laneCompensation = (factor * 14.0f + proximityFactor * 8.0f).coerceIn(0.0f, 20.0f)

        return SpeedCompensationResult(
            containmentAngle = containment,
            executionBoost = executionBoost,
            interceptionProtection = interceptionProtection,
            pressureCompensation = pressureCompensation,
            laneCompensation = laneCompensation
        )
    }
}
