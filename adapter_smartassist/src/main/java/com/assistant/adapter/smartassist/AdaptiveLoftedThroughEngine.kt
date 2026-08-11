package com.assistant.adapter.smartassist

import kotlin.math.hypot
import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine

class AdaptiveLoftedThroughEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    fun executeOptimalLoftedThrough(
        passButtonX: Float,
        passButtonY: Float,
        attackerX: Float,
        attackerY: Float,
        attackerVx: Float,
        attackerVy: Float,
        pitchHeight: Float
    ) {
        // Project where the attacker will be 450ms from now.
        val lookAheadTime = 450f

        val targetLandingX =
            attackerX + (attackerVx * lookAheadTime)

        val targetLandingY =
            (attackerY + (attackerVy * lookAheadTime))
                .coerceIn(0f, pitchHeight)

        val distanceToTarget =
            hypot(
                (targetLandingX - passButtonX).toDouble(),
                (targetLandingY - passButtonY).toDouble()
            ).toFloat()

        // Longer distance = longer hold to carry the ball further.
        val optimizedDuration =
            (90L + (distanceToTarget * 0.12f).toLong())
                .coerceIn(90L, 160L)

        // Swipe gesture direction must point TOWARD the attacker's landing spot.
        // Previously this always swiped straight up 100px, discarding the computed
        // target entirely — every lofted pass went the same direction regardless of
        // where the attacker was running.
        val dx = targetLandingX - passButtonX
        val dy = targetLandingY - passButtonY
        val mag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val gestureRadius = 95f

        val swipeEndX: Float
        val swipeEndY: Float
        if (mag > 1f) {
            swipeEndX = passButtonX + (dx / mag) * gestureRadius
            swipeEndY = passButtonY + (dy / mag) * gestureRadius
        } else {
            // Attacker stationary or no velocity data: default forward loft
            swipeEndX = passButtonX
            swipeEndY = passButtonY - gestureRadius
        }

        inputEngine.injectZeroLatencySwipe(
            passButtonX,
            passButtonY,
            swipeEndX,
            swipeEndY,
            optimizedDuration
        )
    }
}
