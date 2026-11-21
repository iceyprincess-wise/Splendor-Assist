package com.assistant




























import com.assistant.diagnostic.RuntimeLogger




import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource


import com.assistant.runtime.*


























import kotlin.math.*
import kotlin.math.PI

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot






import kotlin.math.sin















class AutoEvadeEngine() {

    companion object {
        @Volatile
        private var lastEvadeTimestamp = 0L

        // BASE COOLDOWN: 132ms (Optimized to align with ≈4 server ticks at 33.33ms each (4 × 33.33 = 133.32 ms; 132 ms is the tuned value))
        private const val BASE_EVADE_COOLDOWN_MS = 132L
        
        // NETCODE ALIGNMENT: Standard game server tick intervals
        private const val SERVER_TICK_MS = 33.33f
        
        // DISPLAY PACING: Hardware refresh rate boundaries
        private const val FRAME_120HZ_MS = 8.33f
        private const val AUTO_EVADE_PHASE = 7 // private parked-lane slot; NOT ActionClass.NONE (ordinal 7)
    }

    fun monitorAttackingSpace(
        myPlayerX: Float, myPlayerY: Float,
        joystickX: Float, joystickY: Float,
        nearestOpponentX: Float, nearestOpponentY: Float,
        oppVxPerSecond: Float, oppVyPerSecond: Float,
        screenWidth: Float = com.assistant.vision.CameraProfile.captureWidthOrFallback(),
        screenHeight: Float = com.assistant.vision.CameraProfile.captureHeightOrFallback()
    ): Boolean {
        val now = System.currentTimeMillis()
        
        val humanizedCooldown = BASE_EVADE_COOLDOWN_MS
        if (now - lastEvadeTimestamp < humanizedCooldown) {
            return false
        }

        // [PREDICTIVE TRACKING] - Project the opponent 2 server ticks into
        // the future (~66 ms). oppVxPerSecond/oppVyPerSecond are px per
        // SECOND (the nearest opponent's own velocity, passed by the
        // caller), so the projection is vPerSecond * 0.0667f — a wall-time
        // horizon, rate-independent (queue item 6). The old form projected
        // 2 frames of a px/frame velocity at an assumed 30 fps; at the
        // 20 fps processed rate that was 100 ms of motion, not 66 ms, and
        // the frame count itself was unmeasured (Q67-6). No frame-rate
        // assumption remains.
        val predictedOppX = nearestOpponentX + (oppVxPerSecond * 0.0667f)
        val predictedOppY = nearestOpponentY + (oppVyPerSecond * 0.0667f)

        val closingDistance = hypot(
            predictedOppX - myPlayerX,
            predictedOppY - myPlayerY
        )

        // [AMPLIFIED INPUT EFFECTIVENESS] - Dynamic threat radius based on velocity magnitude.
        // Queue item 6: the multiplier coefficient is rescaled for the
        // per-second input — 0.0025f is the old 0.05 per-frame coefficient
        // at the 20 fps processed rate (0.05 / 20), so the multiplier's
        // actual on-device behaviour is preserved. Keeping 0.05 on
        // per-second magnitudes would saturate the multiplier at 1.4
        // on every frame.
        val velocityMagnitude = hypot(oppVxPerSecond, oppVyPerSecond)
        val dynamicThreatMultiplier = 1.0f + (velocityMagnitude * 0.0025f).coerceIn(0f, 0.4f)
        val pressRadiusThreshold = (screenHeight * 0.085f) * dynamicThreatMultiplier

        if (closingDistance >= pressRadiusThreshold) {
            return false
        }

        // Calculate geometric approach line vector from player to PREDICTED opponent position
        val approachDx = predictedOppX - myPlayerX
        val approachDy = predictedOppY - myPlayerY

        // Compute base 90-degree perpendicular escape angle
        val approachAngle = atan2(approachDy, approachDx)
        
        val escapeAngle = approachAngle + (PI.toFloat() / 2.0f)

        // Scale gesture path dynamically based on closing speed and screen size
        val evadeRadius = (screenHeight * 0.10f) * dynamicThreatMultiplier
        
        val evadeTargetX = (joystickX + (cos(escapeAngle) * evadeRadius)).coerceIn(0f, screenWidth)
        val evadeTargetY = (joystickY + (sin(escapeAngle) * evadeRadius)).coerceIn(0f, screenHeight)

        // [SERVER-TICK SYNC & FRAME-RATE OPTIMIZATION]
        // Base target: ~38-44ms gesture sweep.
        val rawDuration = 38f 
        
        // Snap duration to the nearest integer ms to a 120 Hz boundary (exact snap is impossible in integer ms)
        val frameAlignedDuration = (ceil((rawDuration / FRAME_120HZ_MS).toDouble()) * FRAME_120HZ_MS).roundToLong()
        
        // Ensure duration structurally bridges at least one server packet boundary to guarantee registration
        val authoritativeDuration = frameAlignedDuration.coerceAtLeast(SERVER_TICK_MS.toLong() + 2L)

        val evadeRequest = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = AUTO_EVADE_PHASE,
            actionClass = ActionClass.EVADE,
            // no59 ITEM 1: carry the engine name so the dispatch log at
            // HybridExecutionTerminal.route() can name this request.
            engine = "AutoEvadeEngine",
            startX = joystickX,
            startY = joystickY,
            endX = evadeTargetX,
            endY = evadeTargetY,
            duration = authoritativeDuration
        )

        // No67-D3: offer() parks SMART_ASSIST requests and always returns
        // false, so the timestamp must be set on the park, not on a
        // success that never comes. The evade still reaches the screen via
        // drainBest() when the contributor lane yields nothing.
        //
        // Item 31 (letter A): the return contract is "threat detected
        // and an evade was parked", NOT "dispatched now". offer()'s
        // false means parked-not-dispatched, not no-threat; returning
        // it here kept the sole reader's evadeActive term
        // (ActiveGestureController.kt, evadeActive * 0.2f in the
        // arbitration stability sum) permanently 0.0f even on a real
        // threat. Returning true on the park restores that term.
        // No threshold, coefficient or cooldown changed: the threat
        // test above (closingDistance < pressRadiusThreshold) is
        // unchanged and reachable in play whenever an opponent closes
        // inside ~8.5% of screen height.
        ContributionRegistry.offer(evadeRequest)
        lastEvadeTimestamp = now
        RuntimeLogger.log(
            "AUTO_EVADE dynamic_slip parked v=(${evadeTargetX.toInt()}, ${evadeTargetY.toInt()}) dur=${authoritativeDuration}ms pred_dist=${closingDistance.toInt()}",
            "SMART_ASSIST"
        )
        return true
    }
}

/* ========
BallCandidate
======== */
