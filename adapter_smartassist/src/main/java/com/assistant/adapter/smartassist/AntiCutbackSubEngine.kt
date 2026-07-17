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
 * Physical isolation and bus-gated anti-cutback defense.
 */
class AntiCutbackSubEngine(
    private val inputEngine: LatencyDefeatingInputEngine
) {

    companion object {
        @Volatile
        private var lastExecutionTimestamp = 0L

        private const val DEBOUNCE_COOLDOWN_MS = 33L
    }

    fun blockCutbackPassingLanes(
        wingerX: Float,
        wingerY: Float,
        myNearestDefenderX: Float,
        myNearestDefenderY: Float,
        penaltyBoxCenterX: Float,
        penaltyBoxCenterY: Float,
        joystickX: Float,
        joystickY: Float,
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

        if (!isWingerAtBaseline) {
            return false
        }

        val depthRange = (screenHeight - baselineThreshold).coerceAtLeast(1f)
        val depthFactor =
            ((wingerY - baselineThreshold) / depthRange).coerceIn(0f, 1f)

        val wingerWeight = 0.35f - (depthFactor * 0.15f)
        val centerWeight = 1.0f - wingerWeight

        val laneMidpointX =
            (wingerX * wingerWeight) +
                (penaltyBoxCenterX * centerWeight)

        val laneMidpointY =
            (wingerY * wingerWeight) +
                (penaltyBoxCenterY * centerWeight)

        val targetAngle = atan2(
            (laneMidpointY - myNearestDefenderY).toDouble(),
            (laneMidpointX - myNearestDefenderX).toDouble()
        )

        val swipeRadius = screenHeight * 0.18f

        val runX =
            (joystickX + (cos(targetAngle) * swipeRadius).toFloat())
                .coerceIn(0f, screenWidth)

        val runY =
            (joystickY + (sin(targetAngle) * swipeRadius).toFloat())
                .coerceIn(0f, screenHeight)

        val request = ExecutionRequest(
            source = ExecutionSource.INTERCEPTION,
            phase = 4,
            startX = joystickX,
            startY = joystickY,
            endX = runX,
            endY = runY,
            duration = 18L
        )

        val submitted = CentralExecutionBus.submit(request)
        if (submitted) {
            lastExecutionTimestamp = now
            RuntimeLogger.log(
                "ANTI_CUTBACK lane blocked vector=($runX, $runY)",
                "DEFENSE"
            )
            return true
        }

        return false
    }
}
