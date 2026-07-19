package com.assistant.adapter.smartassist

import kotlin.random.Random

/**
 * Data representation of the computed bounds for high-performance player agility,
 * stabilization, and body shielding.
 */
data class AgilityResult(
    val shieldActive: Boolean,
    val stabilityBoost: Float,
    val controlRetentionBoost: Float,
    val turnAssist: Float,
    val shieldAngleDegrees: Float,
    val shieldDurationMs: Long
)

/**
 * AgilityEngine
 *
 * A pure, bounded contact-control calculator designed to work in synergy with ShieldAssistEngine.
 * Strengthens movement stability and tight ball-retention under direct pressure from opponents,
 * ensuring fluid counter-runs and turning capability without causing joystick drift.
 */
object AgilityEngine {

    private const val EXPECTED_MAX_VELOCITY = 1.0f

    /**
     * Core calculator for Agility and Ball Retention under pressure.
     * Consumes physical metrics to produce bounded controller multipliers.
     */
    fun computeAgility(
        playerVelocity: Float,
        opponentDistance: Float,
        movementAngleDegrees: Float,
        possessionConfidence: Float,
        turnIntensity: Float,
        playerX: Float? = null,
        playerY: Float? = null,
        oppX: Float? = null,
        oppY: Float? = null
    ): AgilityResult {
        // 1. Normalize physical inputs using 100.0f reference bounds with humanized noise
        val baseProximity = (1.0f - opponentDistance / 220.0f).coerceIn(0.0f, 1.0f)
        val speed = (playerVelocity / EXPECTED_MAX_VELOCITY).coerceIn(0.0f, 1.0f)
        val confidence = possessionConfidence.coerceIn(0.0f, 1.0f)

        // Inject subtle sub-decimal float variance to scramble perfect mathematical linear patterns
        val proximityNoise = Random.nextFloat() * 0.02f - 0.01f // +/- 0.01 fractional variance
        val proximity = (baseProximity + proximityNoise).coerceIn(0.0f, 1.0f)

        // 2. Evaluate shield engagement using ShieldAssistEngine's high-precision authority
        val shieldActive = ShieldAssistEngine.shouldEngageShield(playerVelocity, opponentDistance)

        // 3. Compute Stability Boost [0.0f to 15.0f]
        // Provides the heavy "heft" ball-control feel during physical shoulder duels
        val rawStabilityBoost = if (shieldActive) {
            (4.0f + proximity * 6.0f + speed * 3.0f + confidence * 2.0f).coerceIn(0.0f, 15.0f)
        } else if (opponentDistance > 0f && opponentDistance < 350f) {
            // Provide a soft stabilization baseline even if shield is not fully engaged
            val softProximity = (1.0f - opponentDistance / 350f).coerceIn(0.0f, 1.0f)
            (softProximity * 3.0f * confidence).coerceIn(0.0f, 15.0f)
        } else {
            0.0f
        }
        
        // Add fractional jitter to boost values to disrupt rigid baseline constants in telemetry logs
        val stabilityNoise = if (rawStabilityBoost > 0.0f) Random.nextFloat() * 0.2f - 0.1f else 0.0f
        val stabilityBoost = (rawStabilityBoost + stabilityNoise).coerceIn(0.0f, 15.0f)

        // 4. Compute Control Retention Multiplier [0.0f to 1.0f]
        // Tightens the player's physical grip on the ball during tight-angle pivots
        val controlRetentionBoost = if (confidence > 0.1f) {
            (confidence * 0.6f + proximity * 0.4f).coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }

        // 5. Compute Turn Assist [0.0f to 1.0f]
        // Supports rapid direction changes to dodge incoming tackles without overcommitting
        val turnAssist = if (turnIntensity > 0.15f) {
            (turnIntensity * 0.7f + proximity * 0.3f) * confidence
        } else {
            0.0f
        }.coerceIn(0.0f, 1.0f)

        // 6. Resolve Shield Angle
        // Prioritizes positional coordinates for 180° blocking, falls back to movement-based angle
        val shieldAngleDegreesResolved = if (playerX != null && playerY != null && oppX != null && oppY != null) {
            ShieldAssistEngine.shieldAngle(playerX, playerY, oppX, oppY)
        } else {
            ShieldAssistEngine.shieldAngle(movementAngleDegrees)
        }

        // Introduce organic ms adjustment variance to the hold timing properties
        val dynamicDurationOffset = if (opponentDistance > 0f) Random.nextLong(-3, 4) else 0L

        // 7. Resolve Shield Duration
        val baseShieldDuration = if (opponentDistance > 0f) {
            ShieldAssistEngine.shieldHoldDuration(playerVelocity, opponentDistance)
        } else {
            ShieldAssistEngine.shieldHoldDuration()
        }
        val shieldDurationMsResolved = (baseShieldDuration + dynamicDurationOffset).coerceAtLeast(0L)

        return AgilityResult(
            shieldActive = shieldActive,
            stabilityBoost = stabilityBoost,
            controlRetentionBoost = controlRetentionBoost,
            turnAssist = turnAssist,
            shieldAngleDegrees = shieldAngleDegreesResolved,
            shieldDurationMs = shieldDurationMsResolved
        )
    }
}
