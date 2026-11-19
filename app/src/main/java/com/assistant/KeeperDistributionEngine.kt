package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-J — Keeper Distribution (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G17): after a save or catch the keeper
 * had no distribution choice (throw vs kick vs hold). All 47
 * overlay/interceptor files checked; RecoveryPosition / Prediction
 * handle position only.
 *
 * Choice (pure, from the frame): the keeper has the ball when we have
 * possession AND the keeper is visible AND the ball is within reach of
 * the keeper. Then:
 *  - THROW: a teammate is near (inside throw range) and the keeper is
 *    not hard-pressed — short distribution to the nearest teammate.
 *  - KICK: the keeper is hard-pressed, or no teammate is in throw
 *    range — long distribution to the farthest-upfield teammate toward
 *    the attack end.
 *  - HOLD: hard-pressed AND no upfield teammate exists — no safe
 *    distribution; the contributor stays silent (a hoofed ball to
 *    nobody is a turnover, and a held ball is not).
 *
 * Honest mapping note: the app dispatches swipes, not buttons. A
 * throw vs a kick is expressed through the TARGET, exactly as a pass
 * is: short target = short distribution (throw), long upfield target
 * = long distribution (kick). There is no separate throw/kick button
 * channel in the terminal, and none is invented here.
 */
enum class KeeperDistributionKind { NONE, THROW, KICK, HOLD }

data class KeeperDistributionDecision(
    val kind: KeeperDistributionKind,
    val targetX: Float,
    val targetY: Float,
    val targetEntityId: Int?
)

object KeeperDistributionEngine {

    /** Ball within this of the keeper = keeper has it. */
    const val KEEPER_REACH_FRACTION_OF_HEIGHT = 0.12f
    /** Inside this, a teammate is a throw option. */
    const val THROW_RANGE_FRACTION_OF_WIDTH = 0.35f
    /** Opponent inside this of the keeper = hard-pressed. */
    const val PRESS_RADIUS_FRACTION_OF_WIDTH = 0.15f

    fun choose(frame: RuntimeFrame): KeeperDistributionDecision {
        val none = KeeperDistributionDecision(KeeperDistributionKind.NONE, 0f, 0f, null)
        if (!frame.trusted || !frame.hasBall) return none
        if (!frame.goalkeeperVisible) return none
        if (frame.captureWidth <= 0f || frame.captureHeight <= 0f) return none
        val reach = KEEPER_REACH_FRACTION_OF_HEIGHT * frame.captureHeight
        if (hypot(frame.ballX - frame.goalkeeperX, frame.ballY - frame.goalkeeperY) > reach) {
            return none
        }
        val teammates = frame.entities.filter { it.teammate }
        if (teammates.isEmpty()) {
            return KeeperDistributionDecision(KeeperDistributionKind.HOLD, 0f, 0f, null)
        }
        val pressRadius = PRESS_RADIUS_FRACTION_OF_WIDTH * frame.captureWidth
        val hardPressed = frame.entities.any { e ->
            !e.teammate && hypot(e.x - frame.goalkeeperX, e.y - frame.goalkeeperY) <= pressRadius
        }
        val throwRange = THROW_RANGE_FRACTION_OF_WIDTH * frame.captureWidth
        val nearMate = teammates
            .filter { t -> hypot(t.x - frame.goalkeeperX, t.y - frame.goalkeeperY) <= throwRange }
            .minByOrNull { t -> hypot(t.x - frame.goalkeeperX, t.y - frame.goalkeeperY) }
        if (!hardPressed && nearMate != null) {
            return KeeperDistributionDecision(
                KeeperDistributionKind.THROW, nearMate.x, nearMate.y, nearMate.id
            )
        }
        // Kick: farthest-upfield teammate toward the attack end.
        val attackSign = if (frame.attackDirection < 0) -1f else 1f
        val upfield = teammates.maxByOrNull { t -> t.x * attackSign }
        if (upfield == null) {
            return KeeperDistributionDecision(KeeperDistributionKind.HOLD, 0f, 0f, null)
        }
        return KeeperDistributionDecision(
            KeeperDistributionKind.KICK, upfield.x, upfield.y, upfield.id
        )
    }
}
