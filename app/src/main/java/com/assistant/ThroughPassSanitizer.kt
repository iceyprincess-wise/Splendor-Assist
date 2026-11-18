package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*

import kotlin.math.abs
import kotlin.math.atan2

import kotlin.math.cos







import kotlin.math.sin















class ThroughPassSanitizer(
    private val inputEngine:LatencyDefeatingInputEngine
){

    fun sanitizeAndInjectThroughPass(
        passButtonX:Float,
        passButtonY:Float,
        joystickX:Float,
        joystickY:Float,
        receiverX:Float,
        receiverY:Float,
        nearestDefenderX:Float,
        nearestDefenderY:Float
    ){

        val dx = receiverX - passButtonX
        val dy = receiverY - passButtonY

        val primaryAngle =
            atan2(
                dy.toDouble(),
                dx.toDouble()
            )

        val defDx =
            nearestDefenderX - passButtonX

        val defDy =
            nearestDefenderY - passButtonY

        val defenderAngle =
            atan2(
                defDy.toDouble(),
                defDx.toDouble()
            )

        // No67-D14: wrap-aware — without the wrap, angles straddling ±π
        // read as ~358° instead of ~2° and the dodge never fires.
        val rawDisparity = abs(primaryAngle - defenderAngle)
        val angularDisparity = min(rawDisparity, 2.0 * Math.PI - rawDisparity)

        val sanitizedAngle =
            if(
                angularDisparity <
                Math.toRadians(20.0)
            ){
                if(primaryAngle > defenderAngle)
                    primaryAngle + Math.toRadians(15.0)
                else
                    primaryAngle - Math.toRadians(15.0)
            }else{
                primaryAngle
            }

        val targetJoystickX =
            joystickX +
            (cos(sanitizedAngle) * 80).toFloat()

        val targetJoystickY =
            joystickY +
            (sin(sanitizedAngle) * 80).toFloat()

        inputEngine.injectZeroLatencySwipe(
            passButtonX,
            passButtonY,
            targetJoystickX,
            targetJoystickY,
            75L
        )
    }
}

/* ========
TouchStabilizationEngine
======== */
/**
 * TOUCH STABILIZATION ENGINE
 * Bypasses standard Android input latency queues by directly injecting 
 * mathematically smoothed, high-priority GestureDescriptions.
 */
