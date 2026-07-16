package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * [PRIME AUTHORITATIVE ENGINE] — Physical Isolation & Bus Gated Anti-Cutback Defense
 * Hardened for hyper-responsive budget device input streaming.
 */
class AntiCutbackSubEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    companion object {
        @Volatile
        private var lastExecutionTimestamp = 0L
        // UPGRADE: Throttled down from 110ms to 33ms to match a fluid 30FPS hardware canvas refresh rate
        private const val DEBOUNCE_COOLDOWN_MS = 33L
    }

    fun blockCutbackPassingLanes(
        wingerX: Float, wingerY: Float,
        myNearestDefenderX: Float, myNearestDefenderY: Float,
        penaltyBoxCenterX: Float, penaltyBoxCenterY: Float,
        joystickX: Float, joystickY: Float,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastExecutionTimestamp < DEBOUNCE_COOLDOWN_MS) {
            return false
        }

        val baselineThreshold = screenHeight * 0.80f
        val leftTouchlineThreshold = screenWidth * 0.20f
        val rightTouchlineThreshold = screenWidth * 0.80f

        val isWingerAtBaseline = wingerY > baselineThreshold &&
            (wingerX < leftTouchlineThreshold || wingerX > rightTouchlineThreshold)

        if (!isWingerAtBaseline) return false

        // Weighted midpoint leading 15% toward penalty spot
        val laneMidpointX = (wingerX * 0.4f) + (penaltyBoxCenterX * 0.6f)
        val laneMidpointY = (wingerY * 0.4f) + (penaltyBoxCenterY * 0.6f)

        val targetAngle = atan2(
            (laneMidpointY - myNearestDefenderY).toDouble(),
            (laneMidpointX - myNearestDefenderX).toDouble()
        )

        // UPGRADE: Slightly expand the swipe radius factor to ensure the micro-swipe registers authoritatively on low-spec displays
        val swipeRadius = screenHeight * 0.15f 
        val runX = (joystickX + (cos(targetAngle) * swipeRadius)).toFloat().coerceIn(0f, screenWidth)
        val runY = (joystickY + (sin(targetAngle) * swipeRadius)).toFloat().coerceIn(0f, screenHeight)

        // Route through CentralExecutionBus to prevent Accessibility stroke drops
        val request = ExecutionRequest(
            source = ExecutionSource.INTERCEPTION,
            phase = 4,
            startX = joystickX,
            startY = joystickY,
            endX = runX,
            endY = runY,
            duration = 35L // UPGRADE: Snappier stroke execution path (35ms instead of 45ms) to defeat input latency
        )

        val submitted = CentralExecutionBus.submit(request)
        if (submitted) {
            lastExecutionTimestamp = now
            RuntimeLogger.log("ANTI_CUTBACK lane blocked vector=($runX, $runY)", "DEFENSE")
            return true
        }

        return false
    }
}
