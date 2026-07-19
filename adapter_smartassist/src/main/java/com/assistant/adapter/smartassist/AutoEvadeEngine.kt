package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * [PRIME AUTHORITATIVE ENGINE] — Bus Gated & Predictive Perpendicular Evasion
 * OMEGA UPGRADE ARCHITECTURE:
 * - Predictive Vector Momentum (utilizing oppVx/oppVy for future-state intersection)
 * - Hardware Frame-Rate Sync (120Hz/60Hz refresh quantization)
 * - Adaptive Noise Humanization (Cryptographic micro-variance latency emulation)
 * - Server-Tick Sub-segmentation (Network packet boundary bridging)
 */
@Suppress("UNUSED_PARAMETER", "unused", "MemberVisibilityCanBePrivate")
class AutoEvadeEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    companion object {
        @Volatile
        private var lastEvadeTimestamp = 0L

        // BASE COOLDOWN: 132ms (Optimized to align with exactly 4 server ticks at 33.33ms each)
        private const val BASE_EVADE_COOLDOWN_MS = 132L
        
        // NETCODE ALIGNMENT: Standard game server tick intervals
        private const val SERVER_TICK_MS = 33.33f
        
        // DISPLAY PACING: Hardware refresh rate boundaries
        private const val FRAME_120HZ_MS = 8.33f
    }

    fun monitorAttackingSpace(
        myPlayerX: Float, myPlayerY: Float,
        joystickX: Float, joystickY: Float,
        nearestOpponentX: Float, nearestOpponentY: Float,
        oppVx: Float, oppVy: Float,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): Boolean {
        val now = System.currentTimeMillis()
        val random = ThreadLocalRandom.current()
        
        // [ADAPTIVE NOISE HUMANIZATION] - Dynamic micro-variance to avoid machine pattern detection
        val humanizedCooldown = BASE_EVADE_COOLDOWN_MS + random.nextLong(-12L, 17L)
        if (now - lastEvadeTimestamp < humanizedCooldown) {
            return false
        }

        // [PREDICTIVE TRACKING] - Calculate opponent's future intersection based on momentum
        // Previously unused oppVx/oppVy now project the opponent 2 server ticks into the future (~66ms)
        val predictionScale = (SERVER_TICK_MS * 2f) / 1000f
        val predictedOppX = nearestOpponentX + (oppVx * predictionScale)
        val predictedOppY = nearestOpponentY + (oppVy * predictionScale)

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
        
        // [ADAPTIVE NOISE HUMANIZATION] - Angle micro-variance (-2.8 to +2.8 degrees)
        val angleNoise = (random.nextFloat() - 0.5f) * 0.1f 
        val escapeAngle = approachAngle + (PI.toFloat() / 2.0f) + angleNoise

        // Scale gesture path dynamically based on closing speed and screen size
        val evadeRadius = (screenHeight * 0.10f) * dynamicThreatMultiplier
        
        // [ADAPTIVE NOISE HUMANIZATION] - Spatial micro-variance in exact destination pixels
        val noiseX = (random.nextFloat() - 0.5f) * (screenHeight * 0.015f)
        val noiseY = (random.nextFloat() - 0.5f) * (screenHeight * 0.015f)

        val evadeTargetX = (joystickX + (cos(escapeAngle) * evadeRadius) + noiseX).coerceIn(0f, screenWidth)
        val evadeTargetY = (joystickY + (sin(escapeAngle) * evadeRadius) + noiseY).coerceIn(0f, screenHeight)

        // [SERVER-TICK SYNC & FRAME-RATE OPTIMIZATION]
        // Base target: ~38-44ms gesture sweep.
        val rawDuration = 38f + (random.nextFloat() * 6f) 
        
        // Snap duration to perfectly match 120Hz display boundaries (8.33ms intervals)
        val frameAlignedDuration = (ceil((rawDuration / FRAME_120HZ_MS).toDouble()) * FRAME_120HZ_MS).toLong()
        
        // Ensure duration structurally bridges at least one server packet boundary to guarantee registration
        val authoritativeDuration = frameAlignedDuration.coerceAtLeast(SERVER_TICK_MS.toLong() + 2L)

        val evadeRequest = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = 7,
            startX = joystickX,
            startY = joystickY,
            endX = evadeTargetX,
            endY = evadeTargetY,
            duration = authoritativeDuration
        )

        if (CentralExecutionBus.submit(evadeRequest)) {
            lastEvadeTimestamp = now
            RuntimeLogger.log(
                "AUTO_EVADE dynamic_slip v=(${evadeTargetX.toInt()}, ${evadeTargetY.toInt()}) dur=${authoritativeDuration}ms pred_dist=${closingDistance.toInt()}", 
                "SMART_ASSIST"
            )
            return true
        }

        return false
    }
}
