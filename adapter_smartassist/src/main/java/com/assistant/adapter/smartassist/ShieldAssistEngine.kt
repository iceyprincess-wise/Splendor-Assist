package com.assistant.adapter.smartassist

import kotlin.math.atan2
import kotlin.math.max

object ShieldAssistEngine {

    /**
     * Calculates the perfect protective body-shielding angle.
     * Incorporates anti-jitter wrapping to prevent rapid rotational vibration during contacts.
     */
    fun shieldAngle(
        movementAngle: Float
    ): Float {
        val bounded = movementAngle % 360f
        val angle = if (bounded < -180f) {
            bounded + 360f
        } else if (bounded > 180f) {
            bounded - 360f
        } else {
            bounded
        }
        val result = if (angle >= 0f) angle + 90f else angle - 90f
        return (result % 360f + 360f) % 360f
    }

    /**
     * Advanced positional shield orientation.
     * Overloaded to compute the shielding vector relative to the defender/opponent's position,
     * placing your body exactly opposite (180 degrees) from the incoming tackle threat.
     */
    fun shieldAngle(
        playerX: Float,
        playerY: Float,
        oppX: Float,
        oppY: Float
    ): Float {
        val dx = oppX - playerX
        val dy = oppY - playerY
        val angleRad = atan2(dy.toDouble(), dx.toDouble())
        val angleDeg = Math.toDegrees(angleRad).toFloat()
        // Position player's physical body exactly opposite (180 deg) to block the direct tackle line
        val shieldDir = angleDeg + 180f
        return (shieldDir % 360f + 360f) % 360f
    }

    /**
     * High-precision tactical engagement evaluation.
     * Uses the 100.0f reference divisor to normalize physical space limits.
     * Triggers shield if player velocity exceeds safe thresholds near defenders,
     * OR if immediate proximity threat drops under the critical 1.0f (100.0f) contact distance.
     */
    fun shouldEngageShield(
        playerVelocity: Float,
        opponentDistance: Float
    ): Boolean {
        if (opponentDistance <= 0f) return false
        val normalizedDistance = opponentDistance / 100.0f
        
        // Dynamic Threat Evaluation:
        // 1. Dynamic trigger if opponent is inside 220 coordinate points (2.2f) and player velocity is active.
        // 2. Immediate force override if within critical body collision box of 100.0f (1.0f normalized).
        return normalizedDistance < 2.2f && (playerVelocity > 0.15f || normalizedDistance < 1.0f)
    }

    /**
     * Backward-compatible static shield duration (45ms).
     */
    fun shieldHoldDuration(): Long {
        return 45L
    }

    /**
     * Dynamic shield duration calculation.
     * Dynamically extends body shield duration (up to 120ms) under heavy high-impact pressure,
     * while safely capping it to prevent input lag.
     */
    fun shieldHoldDuration(
        playerVelocity: Float,
        opponentDistance: Float
    ): Long {
        if (opponentDistance <= 0f) return 45L
        val proximityBonus =
            Math.round(
                100.0f / max(opponentDistance / 100.0f, 0.1f)
            )
        val velocityBonus =
            (playerVelocity.coerceAtLeast(0f) * 10f).toLong()
                .coerceIn(0L, 15L)
        val dynamicDuration = 45L + proximityBonus + velocityBonus
        return dynamicDuration.coerceAtLeast(45L).coerceAtMost(120L)
    }
}
