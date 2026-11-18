package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















class AdaptiveLoftedThroughEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    companion object {
        // No68-A-D1: velocity is px per PROCESSED frame (MotionTracker:
        // per-frame delta, no dt division), so a 450 ms look-ahead is
        // 450 ms of frames, not 450 frames. At the 60 fps target rate
        // (game 60 + app 60; the current game-20 setup is being
        // abandoned) that is 27 frames. The old code multiplied by 450
        // directly: 450 frames ahead (7.5 s at 60 fps, 22.5 s at 20 fps),
        // so the "landing spot" was effectively just the velocity
        // direction and the duration saturated at the 160 ms clamp on
        // every moving attacker. The processed rate is still unmeasured
        // on-device (Q67-6); if it is not 60 fps this constant moves.
        const val LOOK_AHEAD_MS = 450f
        const val ASSUMED_FPS = 60f
        val LOOK_AHEAD_FRAMES: Float = LOOK_AHEAD_MS / 1000f * ASSUMED_FPS // 27

        // Pure landing-spot math, extracted so the harness can pin it
        // without an AccessibilityService: the swipe itself can only
        // run on-device.
        fun landingPoint(
            attackerX: Float,
            attackerY: Float,
            attackerVx: Float,
            attackerVy: Float,
            pitchWidth: Float,
            pitchHeight: Float
        ): Pair<Float, Float> = Pair(
            (attackerX + attackerVx * LOOK_AHEAD_FRAMES).coerceIn(0f, pitchWidth),
            (attackerY + attackerVy * LOOK_AHEAD_FRAMES).coerceIn(0f, pitchHeight)
        )

        // Longer distance = longer hold to carry the ball further.
        fun durationFor(distanceToTarget: Float): Long =
            (90L + (distanceToTarget * 0.12f).toLong()).coerceIn(90L, 160L)
    }

    fun executeOptimalLoftedThrough(
        passButtonX: Float,
        passButtonY: Float,
        attackerX: Float,
        attackerY: Float,
        attackerVx: Float,
        attackerVy: Float,
        pitchWidth: Float,
        pitchHeight: Float
    ) {
        // Project where the attacker will be 450ms from now.
        val (targetLandingX, targetLandingY) =
            landingPoint(attackerX, attackerY, attackerVx, attackerVy, pitchWidth, pitchHeight)

        val distanceToTarget =
            hypot(
                (targetLandingX - passButtonX).toDouble(),
                (targetLandingY - passButtonY).toDouble()
            ).toFloat()

        val optimizedDuration = durationFor(distanceToTarget)

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
            // Attacker stationary or no velocity data: default forward
            // loft. No68-A-D3: "forward" is the attack direction (the
            // goal is at the X ends, AttackAxisModel observes it every
            // frame); the old default swiped straight up-screen, which
            // points at neither goal. Fall back to up only while the
            // axis is unsure (direction 0).
            val attackDir = AttackAxisModel.directionIfConfident()
            if (attackDir != 0) {
                swipeEndX = passButtonX + attackDir * gestureRadius
                swipeEndY = passButtonY
            } else {
                swipeEndX = passButtonX
                swipeEndY = passButtonY - gestureRadius
            }
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

/* ========
AgentAction
======== */
/**
 * Actions available to the in-app runtime agent.
 *
 * The agent does not directly manipulate gameplay input.
 * It invokes existing authoritative recovery/orchestration layers.
 */
