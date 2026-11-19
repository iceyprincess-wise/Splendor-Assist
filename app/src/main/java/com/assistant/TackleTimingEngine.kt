package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-K — Tackle Timing (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G5): no tackle timing — the press had
 * no tackle moment. "tackl": 4 files, mentions only.
 *
 * Per the standing rule (wire rather than delete): ShieldAssistEngine
 * was traced first. It holds shield/body-positioning logic only
 * (shieldAngle, shouldEngageShield, shieldHoldDuration) — NO tackle
 * trigger, NO tackle moment. So a new engine is built, as the spec
 * allows ("only build new if it does not [hold the tackle logic]").
 *
 * Window (pure, from the frame): the nearest teammate to the ball
 * (the presser) is inside the tackle radius of the carrier AND the
 * ball is carrier-paced (not a struck ball — a ball moving faster
 * than the struck-ball floor cannot be tackled, only chased) AND the
 * presser is closing (velocity toward the ball > 0). Quality in
 * [0, 1] rises as the presser gets closer. Caller: BallPressContributor
 * (per the spec) — an open window lifts its authority toward 0.9 and
 * shortens its duration hint, so the press becomes a tackle at the
 * right moment instead of a jog at the carrier.
 */
data class TackleWindow(
    val open: Boolean,
    /** 0..1, 1 = on top of the carrier. */
    val quality: Float,
    val presserId: Int?
)

object TackleTimingEngine {

    /** Inside this, the presser can reach the ball. */
    const val TACKLE_RADIUS_FRACTION_OF_HEIGHT = 0.09f
    /** Above this ball speed the ball is struck, not carried. */
    const val STRUCK_BALL_SPEED_PX_PER_S = 150f

    fun window(frame: RuntimeFrame): TackleWindow {
        val closed = TackleWindow(false, 0f, null)
        if (!frame.trusted || frame.hasBall) return closed
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return closed
        val ballSpeed = hypot(frame.ballVelocityXPerSecond, frame.ballVelocityYPerSecond)
        if (!ballSpeed.isFinite() || ballSpeed > STRUCK_BALL_SPEED_PX_PER_S) return closed
        val teammates = frame.entities.filter { it.teammate }
        if (teammates.isEmpty()) return closed
        val radius = TACKLE_RADIUS_FRACTION_OF_HEIGHT * frame.captureHeight
        val presser = teammates.minByOrNull { t -> hypot(t.x - frame.ballX, t.y - frame.ballY) }
            ?: return closed
        val dist = hypot(presser.x - frame.ballX, presser.y - frame.ballY)
        if (dist > radius) return closed
        // Closing: the presser's velocity has a component toward the ball.
        val dx = frame.ballX - presser.x
        val dy = frame.ballY - presser.y
        val d = hypot(dx, dy).coerceAtLeast(1f)
        val closing = (presser.velocityXPerSecond * dx / d +
            presser.velocityYPerSecond * dy / d)
        if (closing <= 0f) return closed
        val quality = (1f - dist / radius).coerceIn(0f, 1f)
        return TackleWindow(true, quality, presser.id)
    }
}
