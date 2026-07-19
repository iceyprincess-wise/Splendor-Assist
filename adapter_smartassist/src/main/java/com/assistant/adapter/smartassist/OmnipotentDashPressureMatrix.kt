package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Dash pressure matrix and anchor preservation.
 */
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

    @Volatile
    private var lastMatrixTimestamp = 0L

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
        joystickX: Float = 250f,
        joystickY: Float = 550f,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): Long {
        val safeWidth = screenWidth.coerceAtLeast(1f)
        val safeHeight = screenHeight.coerceAtLeast(1f)

        entityCoordinates[BALL_X] = ballX
        entityCoordinates[BALL_Y] = ballY
        entityCoordinates[DEF_X] = defX
        entityCoordinates[DEF_Y] = defY
        entityCoordinates[DEF_HOME_X] = defHomeX
        entityCoordinates[DEF_HOME_Y] = defHomeY
        entityCoordinates[OPP_X] = oppX
        entityCoordinates[OPP_Y] = oppY

        val distanceToOpponent = hypot(
            (oppX - defX).toDouble(),
            (oppY - defY).toDouble()
        ).toFloat()

        val compactThreshold = safeWidth * 0.085f

        var targetX: Float
        var targetY: Float

        if (isPlayerHoldingPressure) {
            // Introduce subtle sub-pixel coordinates jitter to ensure vectors show authentic variance
            val matrixNoiseX = Random.nextFloat() * 1.6f - 0.8f // +/- 0.8 pixel tracking wobble
            val matrixNoiseY = Random.nextFloat() * 1.6f - 0.8f

            if (distanceToOpponent > compactThreshold) {
                val interceptAggression = (1f - (distanceToOpponent / safeWidth))
                    .coerceIn(0.25f, 0.85f)

                targetX = ((defHomeX + ((ballX - defHomeX) * interceptAggression)) + matrixNoiseX).coerceIn(0f, safeWidth)
                targetY = ((defHomeY + ((ballY - defHomeY) * interceptAggression)) + matrixNoiseY).coerceIn(0f, safeHeight)
            } else {
                val dx = ballX - oppX
                val dy = ballY - oppY

                val magnitude = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                val dirX = if (magnitude > 0f) dx / magnitude else 0f
                val dirY = if (magnitude > 0f) dy / magnitude else 0f

                val pressureForce = 22f

                targetX = ((ballX + (dirX * pressureForce)) + matrixNoiseX).coerceIn(0f, safeWidth)
                targetY = ((ballY + (dirY * pressureForce)) + matrixNoiseY).coerceIn(0f, safeHeight)
            }
        } else {
            targetX = defHomeX.coerceIn(0f, safeWidth)
            targetY = defHomeY.coerceIn(0f, safeHeight)
        }

        val now = System.currentTimeMillis()
        
        // Dynamic adaptive pacing cooldown window to mask hard 120ms signatures
        val adaptiveCooldownMs = 120L + Random.nextLong(-8, 9) // 112ms to 128ms shifting gate

        if (
            isPlayerHoldingPressure &&
            now - lastMatrixTimestamp >= adaptiveCooldownMs
        ) {
            // Humanize gesture duration subtly by +/- 2ms to blend into normal touch timelines
            val adaptiveDuration = (42L + Random.nextLong(-2, 3)).coerceAtLeast(35L)

            val request = ExecutionRequest(
                source = ExecutionSource.INTERCEPTION,
                phase = 8,
                startX = joystickX,
                startY = joystickY,
                endX = targetX,
                endY = targetY,
                duration = adaptiveDuration
            )

            if (CentralExecutionBus.submit(request)) {
                lastMatrixTimestamp = now

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
