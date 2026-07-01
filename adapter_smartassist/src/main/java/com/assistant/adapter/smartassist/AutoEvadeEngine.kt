package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * [PRIME AUTHORITATIVE ENGINE] — Bus Gated & Geometric Perpendicular Evasion
 * Hardened from raw client field patch to eliminate stationary defender lockouts and bus overlapping.
 */
@Suppress("UNUSED_PARAMETER")
class AutoEvadeEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    companion object {
        @Volatile
        private var lastEvadeTimestamp = 0L
        private const val EVADE_COOLDOWN_MS = 140L
    }

    fun monitorAttackingSpace(
        @Suppress("UNUSED_PARAMETER") 
        myPlayerX: Float, myPlayerY: Float,
        joystickX: Float, joystickY: Float,
        nearestOpponentX: Float, nearestOpponentY: Float,
        oppVx: Float, oppVy: Float,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastEvadeTimestamp < EVADE_COOLDOWN_MS) {
            return false
        }

        val closingDistance = hypot(
            (nearestOpponentX - myPlayerX).toDouble(),
            (nearestOpponentY - myPlayerY).toDouble()
        )

        val pressRadiusThreshold = screenHeight * 0.085f // Normalized threat radius

        if (closingDistance >= pressRadiusThreshold) {
            return false
        }

        // Calculate geometric approach line vector from player to opponent
        val approachDx = nearestOpponentX - myPlayerX
        val approachDy = nearestOpponentY - myPlayerY

        // Compute 90-degree perpendicular escape angle (-dy, dx)
        val approachAngle = atan2(approachDy.toDouble(), approachDx.toDouble())
        val escapeAngle = approachAngle + (Math.PI / 2.0)

        val evadeRadius = screenHeight * 0.10f
        val evadeTargetX = (joystickX + (cos(escapeAngle) * evadeRadius)).toFloat().coerceIn(0f, screenWidth)
        val evadeTargetY = (joystickY + (sin(escapeAngle) * evadeRadius)).toFloat().coerceIn(0f, screenHeight)

        val evadeRequest = ExecutionRequest(
            source = ExecutionSource.SMART_ASSIST,
            phase = 7,
            startX = joystickX,
            startY = joystickY,
            endX = evadeTargetX,
            endY = evadeTargetY,
            duration = 38L
        )

        if (CentralExecutionBus.submit(evadeRequest)) {
            lastEvadeTimestamp = now
            RuntimeLogger.log("AUTO_EVADE defender slipped vector=($evadeTargetX, $evadeTargetY)", "SMART_ASSIST")
            return true
        }

        return false
    }
}
