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
        oppVx: Float, oppVy: Float,
        screenWidth: Float = com.assistant.vision.CameraProfile.captureWidthOrFallback(),
        screenHeight: Float = com.assistant.vision.CameraProfile.captureHeightOrFallback()
    ): Boolean {
        val now = System.currentTimeMillis()
        
        val humanizedCooldown = BASE_EVADE_COOLDOWN_MS
        if (now - lastEvadeTimestamp < humanizedCooldown) {
            return false
        }

        // [PREDICTIVE TRACKING] - Project the opponent 2 server ticks into
        // the future (~66 ms). oppVx/oppVy are px per FRAME (the nearest
        // opponent's own velocity, passed by the caller), so the projection
        // is 2 frames at 30 fps capture — NOT seconds (the old
        // 0.06666 s scale under-predicted by 30x, No67-D2). Capture fps is
        // unmeasured (Q67-6); if it is not 30 fps this constant moves.
        val predictionFrames = 2f
        val predictedOppX = nearestOpponentX + (oppVx * predictionFrames)
        val predictedOppY = nearestOpponentY + (oppVy * predictionFrames)

        val closingDistance = hypot(
            predictedOppX - myPlayerX,
            predictedOppY - myPlayerY
        )

        // [AMPLIFIED INPUT EFFECTIVENESS] - Dynamic threat radius based on velocity magnitude
        val velocityMagnitude = hypot(oppVx, oppVy)
        val dynamicThreatMultiplier = 1.0f + (velocityMagnitude * 0.05f).coerceIn(0f, 0.4f)
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
        ContributionRegistry.offer(evadeRequest)
        lastEvadeTimestamp = now
        RuntimeLogger.log(
            "AUTO_EVADE dynamic_slip parked v=(${evadeTargetX.toInt()}, ${evadeTargetY.toInt()}) dur=${authoritativeDuration}ms pred_dist=${closingDistance.toInt()}",
            "SMART_ASSIST"
        )
        return false
    }
}

/* ========
BallCandidate
======== */
