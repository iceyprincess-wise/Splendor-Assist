package com.assistant

import com.assistant.runtime.RuntimeFrame
import kotlin.math.hypot

/**
 * G-G — Shot-Type Selection (engine-ideas §4).
 *
 * Gap (absence PROVEN, repo map G8): every shot was the same — no
 * chip/finesse/power selection by distance or keeper position.
 *
 * Selection (pure, from the frame's real goal box + keeper):
 *  - CHIP: keeper is stranded WIDE of the goal span (outside the
 *    posts by more than a small margin) — lob the far top corner over
 *    him. The gate uses keeperX vs the goal span ONLY, never
 *    keeperY: keeperY is unset (0) on frames that never saw the
 *    keeper's Y, so any Euclidean keeper distance would fire on a
 *    normally-positioned keeper (proven by the no68 famF pins, whose
 *    keepers sit at keeperY=0). A keeper inside the span is on his
 *    line however far along it he stands.
 *  - POWER: close range — blast the far post low; no time to place.
 *  - FINESSE: mid/long range, keeper on his line — place the far post
 *    at mid height.
 * The type moves the aim INSIDE the goal mouth only (never outside the
 * posts) and adjusts the duration hint. ShotContributor applies it;
 * the type is also logged by the contributor's dispatch line via the
 * engine name (no new log path).
 */
enum class ShotType { POWER, FINESSE, CHIP }

data class ShotTypeDecision(
    val type: ShotType,
    val aimX: Float,
    val aimY: Float,
    val durationHintMs: Long
)

object ShotTypeEngine {

    /** Inside this range the shot is a power blast, not a placement. */
    const val POWER_RANGE_FRACTION_OF_WIDTH = 0.18f
    /** Keeper beyond the posts by more than this (of goal width) is stranded wide. */
    const val KEEPER_STRANDED_MARGIN_OF_GOAL_WIDTH = 0.05f

    fun select(frame: RuntimeFrame, baseAimX: Float, baseAimY: Float): ShotTypeDecision? {
        if (!frame.trusted || !frame.hasBall || !frame.goalDetected) return null
        if (frame.goalWidth <= 1f) return null
        val range = hypot(frame.goalCenterX - frame.ballX, frame.goalCenterY - frame.ballY)
        // Keeper stranded wide of the posts (X only — keeperY is unset
        // on many frames, so a Euclidean distance would misfire).
        val margin = KEEPER_STRANDED_MARGIN_OF_GOAL_WIDTH * frame.goalWidth
        val keeperOffLine = frame.goalkeeperVisible &&
            (frame.goalkeeperX < frame.goalLeftX - margin ||
                frame.goalkeeperX > frame.goalRightX + margin)
        val goalTop = frame.goalTopY
        val goalBottom = frame.goalBottomY
        val goalHeight = (goalBottom - goalTop).coerceAtLeast(1f)
        return when {
            keeperOffLine -> ShotTypeDecision(
                type = ShotType.CHIP,
                aimX = baseAimX,
                // Chip: top corner — high in the mouth, still inside it.
                aimY = (goalTop + goalHeight * 0.10f).coerceIn(goalTop, goalBottom),
                durationHintMs = 25L
            )
            range <= POWER_RANGE_FRACTION_OF_WIDTH * frame.captureWidth -> ShotTypeDecision(
                type = ShotType.POWER,
                aimX = baseAimX,
                // Power: low, near the ground — hardest for the keeper.
                aimY = (goalBottom - goalHeight * 0.15f).coerceIn(goalTop, goalBottom),
                durationHintMs = 35L
            )
            else -> ShotTypeDecision(
                type = ShotType.FINESSE,
                aimX = baseAimX,
                aimY = baseAimY,
                durationHintMs = 30L
            )
        }
    }
}
