package com.assistant

import kotlin.math.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ThroughPassSanitizer(
    private val inputEngine:LatencyDefeatingInputEngine
){

    companion object {
        // Pure dodge math, extracted so the harness can pin it without
        // an AccessibilityService: the swipe itself can only run on-device.
        //
        // No68-A-D11: No67-D14 fixed the disparity MAGNITUDE (wrap-aware
        // min()), but the dodge DIRECTION still compared the raw
        // unwrapped angles — and at the ±π seam that picks the wrong
        // side. Numbered proof: receiver at +170°, defender at −175°
        // (≡ +185°): true disparity 15° < 20°, so the dodge fires;
        // raw comparison +170° > −175° dodges +15° → 185° — EXACTLY the
        // defender's angle. The sanitized pass goes straight at the
        // defender it exists to avoid. The fix wraps the SIGNED delta
        // into (−π, π] and dodges away from the defender's side.
        fun dodgedAngle(primaryAngle: Double, defenderAngle: Double): Double {
            val rawDisparity = abs(primaryAngle - defenderAngle)
            val angularDisparity = min(rawDisparity, 2.0 * Math.PI - rawDisparity)
            if (angularDisparity >= Math.toRadians(20.0)) return primaryAngle
            // Signed short-way offset of the primary line from the
            // defender: atan2(sin, cos) wraps it into (−π, π].
            val signedDelta = atan2(
                sin(primaryAngle - defenderAngle),
                cos(primaryAngle - defenderAngle)
            )
            // signedDelta > 0 means the primary sits counter-clockwise
            // of the defender, so dodging further counter-clockwise
            // (+15°) moves AWAY; signedDelta < 0 dodges clockwise
            // (−15°). Either way the separation grows by 15°; the old
            // raw comparison could shrink it to 0° at the seam.
            return if (signedDelta > 0.0) primaryAngle + Math.toRadians(15.0)
            else primaryAngle - Math.toRadians(15.0)
        }
    }

    /**
     * No68-A-D11 coordinate contract: ALL eight inputs must be in the
     * SAME space — capture pixels (288×640 live). The pass-button and
     * joystick positions are ControlMappingTrainer slot/joystick centres
     * scaled by the capture size (the same derivation
     * RuntimeDecisionLoop.chooseRequest uses); receiver/defender are
     * tracked-player positions, already in capture space. Mixing screen
     * pixels (720×1600) for the buttons with capture pixels for the
     * players would make both angles meaningless.
     *
     * Returns false (without injecting) when any input is non-finite
     * or the bus rejects the swipe, so a caller can tell the swipe was
     * lost instead of assuming it fired.
     *
     * Q67-7 / QA-1 / D13 (batch 2): the caller is now
     * ThroughPassSanitizerGateway (called from
     * RuntimeDecisionLoop.onFrame), which fires only on a fresh
     * physical through-press candidate with a viable through lane.
     * The trigger is a candidate, not a confirmed hardware press —
     * see the gateway's KDoc for what remains blocked.
     */
    fun sanitizeAndInjectThroughPass(
        passButtonX:Float,
        passButtonY:Float,
        joystickX:Float,
        joystickY:Float,
        receiverX:Float,
        receiverY:Float,
        nearestDefenderX:Float,
        nearestDefenderY:Float
    ): Boolean {

        // No68-A-D11: a NaN anywhere would ride straight through
        // atan2/cos/sin into the bus — LatencyDefeatingInputEngine only
        // coerces >= 0, and coerceAtLeast(NaN) returns NaN — so refuse
        // the swipe instead of injecting a NaN gesture.
        if (!passButtonX.isFinite() || !passButtonY.isFinite() ||
            !joystickX.isFinite() || !joystickY.isFinite() ||
            !receiverX.isFinite() || !receiverY.isFinite() ||
            !nearestDefenderX.isFinite() || !nearestDefenderY.isFinite()
        ) return false

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

        val sanitizedAngle = dodgedAngle(primaryAngle, defenderAngle)

        // No68-A-D11: the old end point was joystick + 80 px in the
        // dodged direction with no upper clamp — near an edge it left
        // the capture (the input engine only coerces >= 0). Clamp into
        // the capture so the bus never receives an off-screen end.
        val captureWidth = com.assistant.vision.CameraProfile.captureWidthOrFallback()
        val captureHeight = com.assistant.vision.CameraProfile.captureHeightOrFallback()
        val targetJoystickX =
            (joystickX +
            (cos(sanitizedAngle) * 80).toFloat())
                .coerceIn(0f, captureWidth)

        val targetJoystickY =
            (joystickY +
            (sin(sanitizedAngle) * 80).toFloat())
                .coerceIn(0f, captureHeight)

        return inputEngine.injectZeroLatencySwipe(
            passButtonX,
            passButtonY,
            targetJoystickX,
            targetJoystickY,
            75L
        )
    }
}
