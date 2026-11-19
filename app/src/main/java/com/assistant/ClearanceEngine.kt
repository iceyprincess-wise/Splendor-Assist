package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-I — Clearance Decision (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G16): under pressure the app had no
 * deliberate clearance — only ActiveGestureController
 * .emergencyClearanceVector() as a fallback, no decision engine.
 *
 * Decision: when we have the ball in OUR third and the nearest
 * opponent is inside the pressure radius, clear it — upfield toward
 * the touchline on the attack side (row Z / touchline), not back
 * toward our own goal. The target is a real point: attack-direction
 * edge of the capture, on the nearer touchline (top/bottom edge).
 * ClearanceContributor offers it as a SHOT-class kick (a clearance
 * IS a kick toward a target; the shoot button is the kick input, and
 * requiredControlSlot routes SHOT from slot 2 like any shot). No
 * clearance fires when we are not under pressure — recycling
 * possession beats hoofing it.
 */
data class ClearanceDecision(
    val clear: Boolean,
    val targetX: Float,
    val targetY: Float
)

object ClearanceEngine {

    /** Inside this, the carrier is under pressure. */
    const val PRESSURE_RADIUS_FRACTION_OF_HEIGHT = 0.18f

    fun decide(frame: RuntimeFrame): ClearanceDecision {
        val none = ClearanceDecision(false, 0f, 0f)
        if (!frame.trusted || !frame.hasBall) return none
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return none
        // Own third: same convention as BallOnlyDetector — opposite the
        // attack direction. Attack +X => own third is x < W/3.
        val ownThird = if (frame.attackDirection < 0) frame.ballX > frame.captureWidth * 2f / 3f
            else frame.ballX < frame.captureWidth / 3f
        if (!ownThird) return none
        val opponents = frame.entities.filter { !it.teammate }
        if (opponents.isEmpty()) return none
        val nearestDist = opponents.minOf { e -> hypot(e.x - frame.ballX, e.y - frame.ballY) }
        if (nearestDist > PRESSURE_RADIUS_FRACTION_OF_HEIGHT * frame.captureHeight) return none
        val attackSign = if (frame.attackDirection < 0) -1f else 1f
        val w = frame.captureWidth
        val h = frame.captureHeight
        // Upfield touchline: attack-direction edge, nearer touchline.
        val targetX = if (attackSign > 0) w * 0.92f else w * 0.08f
        val targetY = if (frame.ballY <= h * 0.5f) h * 0.05f else h * 0.95f
        return ClearanceDecision(true, targetX, targetY)
    }
}
