package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource
import kotlin.math.hypot
import kotlin.random.Random
import com.assistant.execution.ContributionRegistry

// FIX: removed 110ms cooldown (throttled to 9 gestures/sec, arrived 94ms late).
// Now 16ms = one gesture per vision frame — zero-delay intercept arrival.
@Suppress("UNUSED_PARAMETER","UNUSED_VARIABLE")
object HybridOmnipotentMatrixEngine {

    private val physicsMatrix = FloatArray(8)
    private const val ATTACKER_X=0; private const val ATTACKER_Y=1
    private const val ATTACKER_VX=2; private const val ATTACKER_VY=3
    private const val BALL_X=4; private const val BALL_Y=5
    private const val BALL_VX=6; private const val BALL_YV=7
    @Volatile private var lastMatrixTimestamp = 0L

    fun computeGodspeedInterceptVector(
        myPlayerX:Float, myPlayerY:Float,
        oppPlayerX:Float, oppPlayerY:Float,
        oppVx:Float, oppVy:Float,
        ballX:Float, ballY:Float,
        ballVx:Float, ballVy:Float,
        isOpponentExecutingSkill:Boolean,
        joystickX:Float=250f, joystickY:Float=550f,
        screenWidth:Float=1650f, screenHeight:Float=720f
    ): Long {
        physicsMatrix[ATTACKER_X]=oppPlayerX; physicsMatrix[ATTACKER_Y]=oppPlayerY
        physicsMatrix[ATTACKER_VX]=oppVx;     physicsMatrix[ATTACKER_VY]=oppVy
        physicsMatrix[BALL_X]=ballX;          physicsMatrix[BALL_Y]=ballY
        physicsMatrix[BALL_VX]=ballVx;        physicsMatrix[BALL_YV]=ballVy

        val ballToOpp = hypot((ballX-oppPlayerX).toDouble(),(ballY-oppPlayerY).toDouble()).toFloat()
        val lookAhead = if (isOpponentExecutingSkill) 1.2f else 2.8f
        val loose     = screenWidth * 0.045f
        val nx = Random.nextFloat()*1.4f-0.7f; val ny = Random.nextFloat()*1.4f-0.7f

        val targetX: Float; val targetY: Float
        if (ballToOpp > loose) {
            targetX = (ballX + ballVx*lookAhead + nx).coerceIn(0f,screenWidth)
            targetY = (ballY + ballVy*lookAhead + ny).coerceIn(0f,screenHeight)
        } else {
            val mag = hypot(oppVx.toDouble(),oppVy.toDouble()).toFloat()
            val nvx = if(mag>0.05f) oppVx/mag else (ballX-myPlayerX)*0.1f
            val nvy = if(mag>0.05f) oppVy/mag else (ballY-myPlayerY)*0.1f
            val br  = screenHeight*0.03f
            targetX = (oppPlayerX + nvx*br + nx).coerceIn(0f,screenWidth)
            targetY = (oppPlayerY + nvy*br + ny).coerceIn(0f,screenHeight)
        }

        val now = System.currentTimeMillis()
        if (now - lastMatrixTimestamp >= 16L) {
            val req = ExecutionRequest(
                source=ExecutionSource.INTERCEPTION, phase=9,
                startX=joystickX, startY=joystickY,
                endX=targetX, endY=targetY, duration=40L)
            if (ContributionRegistry.offer(req)) {
                lastMatrixTimestamp = now
                RuntimeLogger.log("GODSPEED_INTERCEPT target=($targetX,$targetY) dur=40ms","DEFENSE")
            }
        }
        val px = targetX.toBits().toLong(); val py = targetY.toBits().toLong()
        return (px shl 32) or (py and 0xFFFFFFFFL)
    }

    fun unpackX(packed:Long):Float = Float.fromBits((packed shr 32).toInt())
    fun unpackY(packed:Long):Float = Float.fromBits(packed.toInt())
}
