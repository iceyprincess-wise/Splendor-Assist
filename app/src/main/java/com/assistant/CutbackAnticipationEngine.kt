package com.assistant






































import com.assistant.runtime.RuntimeEntity




























import kotlin.math.*




























/*
 * Dribble-cutback anticipation (queue item 11, F4) — pure.
 *
 * Ed's scenario: the opponent carrier drags our third-last defender
 * LATERALLY (along Y, the flank axis), then turns the ball the other
 * way (right leg), shoulder-barges past the turned defender, and the
 * space opens for a 1v1. The defender must LEAVE the drag side and
 * cover the CUT side — the space about to open — reading the cutback
 * BEFORE it happens. This is a DRIBBLE cutback, not the winger-at-
 * baseline cutback PASS AntiCutbackSubEngine already covers (that one
 * is live via ActiveGestureController and is untouched here).
 *
 * Trigger (all measured, no invented number):
 *   - the ball is in OUR defensive third (own-third convention by
 *     attackDirection — the same code shape WingBlockContributor uses);
 *   - the carrier is moving laterally and really moving:
 *     |vyPerSecond| >= ThroughBallLaneAnalysisEngine.
 *     RUN_DIRECTION_MIN_PX_PER_S (50 px/s — the repo's "really moving"
 *     threshold) AND lateral-dominant (|vy| > |vx| — he is moving
 *     sideways, not toward goal);
 *   - a user defender is engaged with him (within
 *     BallOwnershipEngine.MAX_OWNERSHIP_DISTANCE = 60 px, the repo's
 *     possession rule) and moving laterally the SAME direction (same
 *     sign vy — a stationary man is not being dragged).
 * Target: the channel on the CUT side (opposite the drag — "leave the
 * left, cover the right"): the midpoint between the dragged defender
 * and the next user defender on the cut side (the gap the burst will
 * go through). If no defender stands on the cut side, the channel is
 * between the dragged defender and the keeper — midpoint(dragged,
 * keeper) when the keeper is visible, else NO offer (never invent a
 * point). "Left"/"right" are never named in code — only drag side vs
 * cut side (signs), so the logic is orientation-agnostic.
 *
 * The cover point is one channel away on the line (the midpoint to
 * the adjacent man), so no time-to-arrive check is needed — unlike
 * the lane intercept, this is a short move, and the offer drops the
 * frame the carrier stops moving laterally, bounding the risk if he
 * never cuts. The keeper entity, when present among userEntities, is
 * simply the nearest man on the cut side if he stands there — the
 * midpoint to him is the same point the keeper fallback gives, so no
 * special-casing is needed here (the contributor excludes the keeper
 * from the DRAGGED search, where he would be a degenerate pick).
 */
object CutbackAnticipationEngine {

    data class CutbackCover(
        val targetX: Float,
        val targetY: Float,
        val draggedDefenderId: Int
    )

    fun analyze(
        carrierVxPerSecond: Float, carrierVyPerSecond: Float,
        dragged: RuntimeEntity,
        userEntities: List<RuntimeEntity>,
        keeperX: Float, keeperY: Float, keeperVisible: Boolean,
        ballX: Float,
        attackDirection: Int,
        captureWidth: Float, captureHeight: Float
    ): CutbackCover? {
        if (!captureWidth.isFinite() || !captureHeight.isFinite() ||
            captureWidth <= 0f || captureHeight <= 0f
        ) return null
        if (!ballX.isFinite()) return null

        // Own third only — a drag in midfield or the attacking third is
        // not a cutback threat (same convention as WingBlockContributor).
        val attackSign = if (attackDirection < 0) -1f else 1f
        val inOwnThird = if (attackSign > 0) ballX < captureWidth / 3f
            else ballX > 2f * captureWidth / 3f
        if (!inOwnThird) return null

        // Lateral drag: really moving sideways, not toward goal.
        val lateralSpeed = abs(carrierVyPerSecond)
        if (lateralSpeed < ThroughBallLaneAnalysisEngine.RUN_DIRECTION_MIN_PX_PER_S) return null
        if (lateralSpeed <= abs(carrierVxPerSecond)) return null

        // The dragged man moves laterally the SAME direction (same sign
        // vy — a stationary man is not being dragged).
        if (dragged.velocityYPerSecond * carrierVyPerSecond <= 0f) return null

        // Cut side = opposite the drag.
        val cutSide = if (carrierVyPerSecond > 0f) -1f else 1f
        // Next user defender on the cut side: nearest in Y — the adjacent
        // man on the (vertical) line.
        val next = userEntities
            .filter { it.id != dragged.id }
            .filter { (it.y - dragged.y) * cutSide > 0f }
            .minByOrNull { abs(it.y - dragged.y) }

        val targetX: Float
        val targetY: Float
        if (next != null) {
            targetX = (dragged.x + next.x) * 0.5f
            targetY = (dragged.y + next.y) * 0.5f
        } else if (keeperVisible) {
            targetX = (dragged.x + keeperX) * 0.5f
            targetY = (dragged.y + keeperY) * 0.5f
        } else {
            return null
        }
        return CutbackCover(
            targetX = targetX.coerceIn(0f, captureWidth),
            targetY = targetY.coerceIn(0f, captureHeight),
            draggedDefenderId = dragged.id
        )
    }
}
