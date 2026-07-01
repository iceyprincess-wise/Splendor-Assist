package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import kotlin.math.hypot

/**
 * [PRIME AUTHORITATIVE ENGINE] — Dash Pressure Matrix & Anchor Preservation
 * Hardened from raw field patch to normalize distance ratios and unpack directly into bus.
 */
@Suppress("UNUSED_PARAMETER")
object OmnipotentDashPressureMatrix {

    private val entityCoordinates = FloatArray(8)
    private const val BALL_X = 0
    private const val BALL_Y = 1
    private const val DEF_X = 2
    private const val DEF_Y = 3
    private const val DEF_HOME_X = 4
    private const val DEF_HOME_Y = 5
    private const val OPP_X = 6
    private const val OPP_Y = 7
    @Volatile private var lastMatrixTimestamp = 0L

    fun computeHighAuthorityDefensiveVector(
        ballX: Float, ballY: Float,
        defX: Float, defY: Float,
        defHomeX: Float, defHomeY: Float,
        oppX: Float, oppY: Float,
        isPlayerHoldingPressure: Boolean,
        joystickX: Float = 250f, joystickY: Float = 550f,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): Long {
        entityCoordinates[BALL_X] = ballX
        entityCoordinates[BALL_Y] = ballY
        entityCoordinates[DEF_X] = defX
        entityCoordinates[DEF_Y] = defY
        entityCoordinates[DEF_HOME_X] = defHomeX
        entityCoordinates[DEF_HOME_Y] = defHomeY
        entityCoordinates[OPP_X] = oppX
        entityCoordinates[OPP_Y] = oppY

        val distanceToOpponent = hypot((oppX - defX).toDouble(), (oppY - defY).toDouble()).toFloat()
        val compactThreshold = screenWidth * 0.085f

        val targetX: Float
        val targetY: Float

        if (isPlayerHoldingPressure) {
            if (distanceToOpponent > compactThreshold) {
                targetX = defHomeX + (ballX - defHomeX) * 0.25f
                targetY = defHomeY + (ballY - defHomeY) * 0.25f
            } else {
                val dx = ballX - oppX
                val dy = ballY - oppY
                val mag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                
                val dirX = if (mag > 0) dx / mag else 0f
                val dirY = if (mag > 0) dy / mag else 0f

                targetX = (ballX + (dirX * 22f)).coerceIn(0f, screenWidth)
                targetY = (ballY + (dirY * 22f)).coerceIn(0f, screenHeight)
            }
        } else {
            targetX = defHomeX
            targetY = defHomeY
        }

        val now = System.currentTimeMillis()
        if (isPlayerHoldingPressure && now - lastMatrixTimestamp >= 120L) {
            val request = ExecutionRequest(
                source = ExecutionSource.INTERCEPTION,
                phase = 8,
                startX = joystickX,
                startY = joystickY,
                endX = targetX,
                endY = targetY,
                duration = 42L
            )
            if (CentralExecutionBus.submit(request)) {
                lastMatrixTimestamp = now
                RuntimeLogger.log("DASH_PRESSURE matrix executed target=($targetX, $targetY)", "DEFENSE")
            }
        }

        val packedX = targetX.toBits().toLong()
        val packedY = targetY.toBits().toLong()
        return (packedX shl 32) or (packedY and 0xFFFFFFFFL)
    }

    fun unpackX(packed: Long): Float = Float.fromBits((packed shr 32).toInt())
    fun unpackY(packed: Long): Float = Float.fromBits(packed.toInt())
}
