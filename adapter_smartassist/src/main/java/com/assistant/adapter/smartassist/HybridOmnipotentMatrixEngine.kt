package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.CentralExecutionBus
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import kotlin.math.hypot

/**
 * [PRIME AUTHORITATIVE ENGINE] — Hybrid Omnipotent Matrix & Focal Target Intercept
 * Hardened from raw field patch to normalize distance bounds and unpack directly into bus.
 */
@Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")
object HybridOmnipotentMatrixEngine {

    private val physicsMatrix = FloatArray(8) 
    private const val ATTACKER_X = 0
    private const val ATTACKER_Y = 1
    private const val ATTACKER_VX = 2
    private const val ATTACKER_VY = 3
    private const val BALL_X = 4
    private const val BALL_Y = 5
    private const val BALL_VX = 6
    private const val BALL_YV = 7
    @Volatile private var lastMatrixTimestamp = 0L

    fun computeGodspeedInterceptVector(
        myPlayerX: Float, myPlayerY: Float,
        oppPlayerX: Float, oppPlayerY: Float,
        oppVx: Float, oppVy: Float,
        ballX: Float, ballY: Float,
        ballVx: Float, ballVy: Float,
        isOpponentExecutingSkill: Boolean,
        joystickX: Float = 250f, joystickY: Float = 550f,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): Long {
        physicsMatrix[ATTACKER_X] = oppPlayerX
        physicsMatrix[ATTACKER_Y] = oppPlayerY
        physicsMatrix[ATTACKER_VX] = oppVx
        physicsMatrix[ATTACKER_VY] = oppVy
        physicsMatrix[BALL_X] = ballX
        physicsMatrix[BALL_Y] = ballY
        physicsMatrix[BALL_VX] = ballVx
        physicsMatrix[BALL_YV] = ballVy

        val ballToOpponentDistance = hypot(
            (ballX - oppPlayerX).toDouble(), 
            (ballY - oppPlayerY).toDouble()
        ).toFloat()

        val lookAheadFrames = if (isOpponentExecutingSkill) 1.2f else 2.8f
        val looseBallThreshold = screenWidth * 0.045f

        val targetX: Float
        val targetY: Float

        if (ballToOpponentDistance > looseBallThreshold) {
            targetX = (ballX + (ballVx * lookAheadFrames)).coerceIn(0f, screenWidth)
            targetY = (ballY + (ballVy * lookAheadFrames)).coerceIn(0f, screenHeight)
        } else {
            val movementMagnitude = hypot(oppVx.toDouble(), oppVy.toDouble()).toFloat()
            val normVx = if (movementMagnitude > 0.05f) oppVx / movementMagnitude else (ballX - myPlayerX) * 0.1f
            val normVy = if (movementMagnitude > 0.05f) oppVy / movementMagnitude else (ballY - myPlayerY) * 0.1f

            val blockRadius = screenHeight * 0.03f
            targetX = (oppPlayerX + (normVx * blockRadius)).coerceIn(0f, screenWidth)
            targetY = (oppPlayerY + (normVy * blockRadius)).coerceIn(0f, screenHeight)
        }

        val now = System.currentTimeMillis()
        if (now - lastMatrixTimestamp >= 110L) {
            val closingDx = targetX - myPlayerX
            val closingDy = targetY - myPlayerY
            
            val request = ExecutionRequest(
                source = ExecutionSource.INTERCEPTION,
                phase = 9,
                startX = joystickX,
                startY = joystickY,
                endX = targetX,
                endY = targetY,
                duration = 40L
            )
            if (CentralExecutionBus.submit(request)) {
                lastMatrixTimestamp = now
                RuntimeLogger.log("GODSPEED_INTERCEPT executed target=($targetX, $targetY)", "DEFENSE")
            }
        }

        val packedX = targetX.toBits().toLong()
        val packedY = targetY.toBits().toLong()
        return (packedX shl 32) or (packedY and 0xFFFFFFFFL)
    }

    fun unpackX(packed: Long): Float = Float.fromBits((packed shr 32).toInt())
    fun unpackY(packed: Long): Float = Float.fromBits(packed.toInt())
}
