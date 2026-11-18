package com.assistant




























import com.assistant.diagnostic.RuntimeLogger




import com.assistant.execution.ContributionRegistry
import com.assistant.execution.ExecutionRequest
import com.assistant.execution.ExecutionSource


import com.assistant.runtime.*


























import kotlin.math.*





import kotlin.math.hypot






















object HybridOmnipotentMatrixEngine {

    // No68-H-D14: physicsMatrix (FloatArray(8)) + BALL_X/BALL_Y/BALL_VX/
    // BALL_YV index consts removed — never written or read anywhere.
    @Volatile private var lastMatrixTimestamp = 0L

    fun computeGodspeedInterceptVector(
        myPlayerX:Float, myPlayerY:Float,
        oppPlayerX:Float, oppPlayerY:Float,
        oppVx:Float, oppVy:Float,
        ballX:Float, ballY:Float,
        ballVx:Float, ballVy:Float,
        isOpponentExecutingSkill:Boolean,
        // No68-H-D16: the joystick rest is the TRAINED centre (0.182w,
        // 0.648h), not the stale 250f/550f landscape constants — the old
        // defaults put the emergency gesture's start ~500px off the live
        // joystick on screen (625,1375 vs 131,1037 at 720x1600).
        joystickX:Float=com.assistant.vision.CameraProfile.captureWidthOrFallback() * ControlMappingTrainer.joystickCenterX(),
        joystickY:Float=com.assistant.vision.CameraProfile.captureHeightOrFallback() * ControlMappingTrainer.joystickCenterY(),
        // No68-H-D15: contributors own the ConAI path — when false the
        // matrix is a pure function and offers nothing, so one decision
        // dispatches once instead of twice (emergency offer + contribution).
        offerEmergency:Boolean=true,
        screenWidth:Float=com.assistant.vision.CameraProfile.captureWidthOrFallback(),
        screenHeight:Float=com.assistant.vision.CameraProfile.captureHeightOrFallback()
    ): Long {
        val ballToOpp = hypot((ballX-oppPlayerX).toDouble(),(ballY-oppPlayerY).toDouble()).toFloat()
        val lookAhead = if (isOpponentExecutingSkill) 1.2f else 2.8f
        val loose     = screenWidth * 0.045f

        val targetX: Float; val targetY: Float
        if (ballToOpp > loose) {
            targetX = (ballX + ballVx*lookAhead).coerceIn(0f,screenWidth)
            targetY = (ballY + ballVy*lookAhead).coerceIn(0f,screenHeight)
        } else {
            val mag = hypot(oppVx.toDouble(),oppVy.toDouble()).toFloat()
            // No68-H-D17: the fallback direction was NOT a unit vector —
            // (ball-my)*0.1 grows with distance (up to ~60x), so the target
            // (opp + dir*br) rocketed to a screen edge on every tight
            // marking frame (all live callers pass oppV=0, so this branch
            // always ran). Normalise it like the live branch.
            val fallbackDx = ballX - myPlayerX
            val fallbackDy = ballY - myPlayerY
            val fallbackMag = hypot(fallbackDx.toDouble(), fallbackDy.toDouble()).toFloat()
            val nvx = if (mag > 0.05f) oppVx / mag else if (fallbackMag > 0.05f) fallbackDx / fallbackMag else 0f
            val nvy = if (mag > 0.05f) oppVy / mag else if (fallbackMag > 0.05f) fallbackDy / fallbackMag else 0f
            val br  = screenHeight*0.03f
            targetX = (oppPlayerX + nvx*br).coerceIn(0f,screenWidth)
            targetY = (oppPlayerY + nvy*br).coerceIn(0f,screenHeight)
        }

        val now = System.currentTimeMillis()
        // No64-D30 (F9): the Interception switch gates this INT emergency
        // offer — it used to offer every 16 ms while the switch was off.
        if (offerEmergency &&
            com.assistant.overlay.interceptor.InterceptionRuntimeRegistry.enabled &&
            com.assistant.overlay.interceptor.InterceptionRuntimeRegistry.autoIntercept &&
            now - lastMatrixTimestamp >= 16L) {
            val req = ExecutionRequest(
                source=ExecutionSource.INTERCEPTION, phase=9,
                actionClass=com.assistant.runtime.ActionClass.DEFEND,
                // no59 ITEM 1: carry the engine name to the dispatch log.
                engine="HybridOmnipotentMatrixEngine",
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
