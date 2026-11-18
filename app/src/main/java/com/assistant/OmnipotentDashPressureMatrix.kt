package com.assistant




























import com.assistant.diagnostic.RuntimeLogger




import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource


import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object OmnipotentDashPressureMatrix {

    // No68-H-D21: entityCoordinates (FloatArray(8)) + its BALL_/DEF_/OPP_
    // index consts removed — written on every call, never read anywhere.
    // No68-I-D34: the entityCoordinates array (and its BALL_X..OPP_Y index
    // consts) was WRITE-ONLY — written on every compute, never read by any
    // code in the repo. Deleted with its writes.
    @Volatile
    private var lastMatrixTimestamp = 0L

    // No68-I-D32: set on every compute() — true only when the direct
    // INTERCEPTION offer below was accepted by the bus this call, so the
    // caller (DashPressureContributor) can skip its duplicate ConAI
    // contribution for an already-dispatched press.
    @Volatile
    var lastDirectOfferAccepted = false
        private set

    fun computeHighAuthorityDefensiveVector(
        ballX: Float,
        ballY: Float,
        defX: Float,
        defY: Float,
        defHomeX: Float,
        defHomeY: Float,
        oppX: Float,
        oppY: Float,
        isPlayerHoldingPressure: Boolean,
        // No68-H-D23: same stale-default fix as Hybrid (No68-H-D16) — the
        // trained joystick centre, not 250f/550f.
        joystickX: Float = com.assistant.vision.CameraProfile.captureWidthOrFallback() * ControlMappingTrainer.joystickCenterX(),
        joystickY: Float = com.assistant.vision.CameraProfile.captureHeightOrFallback() * ControlMappingTrainer.joystickCenterY(),
        // No68-H-D22: contributors own the ConAI path — when false the
        // matrix is a pure function and offers nothing (see No68-H-D15).
        offerEmergency: Boolean = true,
        screenWidth: Float = com.assistant.vision.CameraProfile.captureWidthOrFallback(),
        screenHeight: Float = com.assistant.vision.CameraProfile.captureHeightOrFallback()
    ): Long {
        val safeWidth = screenWidth.coerceAtLeast(1f)
        val safeHeight = screenHeight.coerceAtLeast(1f)

        // No68-I-D32: the offer flag is per-compute; reset it here so a
        // stale true from an earlier call can never suppress a later
        // contribution.
        lastDirectOfferAccepted = false

        val distanceToOpponent = hypot(
            (oppX - defX).toDouble(),
            (oppY - defY).toDouble()
        ).toFloat()

        val compactThreshold = safeWidth * 0.085f

        var targetX: Float
        var targetY: Float

        if (isPlayerHoldingPressure) {
            if (distanceToOpponent > compactThreshold) {
                val interceptAggression = (1f - (distanceToOpponent / safeWidth))
                    .coerceIn(0.25f, 0.85f)

                targetX = (defHomeX + ((ballX - defHomeX) * interceptAggression)).coerceIn(0f, safeWidth)
                targetY = (defHomeY + ((ballY - defHomeY) * interceptAggression)).coerceIn(0f, safeHeight)
            } else {
                // No68-I-D33: both callers pass opp == ball (the carrier
                // holds the ball), so the old direction (ball - opp) was
                // always (0, 0) and the 22 px pressure push never happened.
                // Push past the ball along the defender's run-in line
                // (ball - def) instead, keeping the same 22f force.
                val dx = ballX - defX
                val dy = ballY - defY

                val magnitude = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                val dirX = if (magnitude > 0f) dx / magnitude else 0f
                val dirY = if (magnitude > 0f) dy / magnitude else 0f

                val pressureForce = 22f

                targetX = (ballX + (dirX * pressureForce)).coerceIn(0f, safeWidth)
                targetY = (ballY + (dirY * pressureForce)).coerceIn(0f, safeHeight)
            }
        } else {
            targetX = defHomeX.coerceIn(0f, safeWidth)
            targetY = defHomeY.coerceIn(0f, safeHeight)
        }

        targetX = targetX.coerceIn(0f, safeWidth)
        targetY = targetY.coerceIn(0f, safeHeight)
        val now = System.currentTimeMillis()
        
        // No68-H-D24: fixed 40ms cooldown — the old comment claimed it
        // "was 40ms +/- 4ms random jitter feeding a live request", but no
        // jitter exists in the code; the value is a plain constant.
        val adaptiveCooldownMs = 40L // Fixed 40ms, no jitter.

        // No64-D30 (F9): the Interception switch gates this INT emergency
        // offer — it used to offer unconditionally while the switch was off.
        if (
            offerEmergency &&
            com.assistant.overlay.interceptor.InterceptionRuntimeRegistry.enabled &&
            com.assistant.overlay.interceptor.InterceptionRuntimeRegistry.autoIntercept &&
            isPlayerHoldingPressure &&
            now - lastMatrixTimestamp >= adaptiveCooldownMs
        ) {
            // No68-H-D24: fixed 42ms duration — the old comment claimed
            // "+/- 2ms humanisation", but no jitter exists in the code.
            val adaptiveDuration = 42L

            val request = ExecutionRequest(
                source = ExecutionSource.INTERCEPTION,
                phase = 8,
                actionClass = com.assistant.runtime.ActionClass.DEFEND,
                // no59 ITEM 1: carry the engine name to the dispatch log.
                engine = "OmnipotentDashPressureMatrix",
                startX = joystickX,
                startY = joystickY,
                endX = targetX,
                endY = targetY,
                duration = adaptiveDuration
            )

            if (ContributionRegistry.offer(request)) {
                lastMatrixTimestamp = now
                // No68-I-D32: the press dispatched on the emergency lane —
                // the contributor must not also offer it through ConAI.
                lastDirectOfferAccepted = true

                RuntimeLogger.log(
                    "DASH_PRESSURE matrix executed target=($targetX, $targetY) duration=${adaptiveDuration}ms",
                    "DEFENSE"
                )
            }
        }

        val packedX = targetX.toBits().toLong()
        val packedY = targetY.toBits().toLong()

        return (packedX shl 32) or (packedY and 0xFFFFFFFFL)
    }

    fun unpackX(packed: Long): Float =
        Float.fromBits((packed shr 32).toInt())

    fun unpackY(packed: Long): Float =
        Float.fromBits(packed.toInt())
}

/* ========
OnlineParameterAdaptationEngine
======== */
