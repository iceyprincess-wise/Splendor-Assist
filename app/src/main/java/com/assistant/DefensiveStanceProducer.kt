package com.assistant

/**
 * L13 — Defensive Stance Producer (DefensiveStanceProducer).
 *
 * Gap (Q68-85): the LongBall ↔ DefensiveLine handshake was missing because
 * the stance geometry itself was undefined in-repo — no stance producer
 * existed.
 *
 * Contract: in = ThreatDecision + `computeUserLine` output; out =
 * defensive stance (line position + shape); caller = VisionCore (after
 * DefensiveLineEngine.computeUserLine — this is that function's first
 * live caller); consumer = the defensive-line contributors (via
 * RuntimeFrame: defensiveLineX / defensiveLineSpread / stanceTight /
 * dragOutHeld) + LowBlockContainmentEngine (when wired — its slot span
 * reads the stance through the frame in the next round).
 *
 * D14 (Ed, §5): the stance spec is TIGHT defensive line + TIGHT
 * midfield, no break under aggressive drag-out. That is implemented as
 * clamps, not as following the players:
 *   - line spread (maxX-minX of the user line) is clamped to 25% of the
 *     capture width; the midfield spread to 30%.
 *   - the midfield sits one step ahead of the line toward the ball
 *     (12% of the width), so line and midfield stay compact together.
 *   - when a drag-out stretches the line past the tight bound, the stance
 *     does NOT follow the dragged player: the spread stays clamped and
 *     dragOutHeld=true names the hold. The line holds its shape instead
 *     of breaking.
 * The stance is a pure function of the user line + ball X: no threat
 * input is needed for the geometry (the ThreatDecision selects which
 * defensive behaviour runs; the stance only says where the line stands).
 * A missing user line reads found=false — never a fabricated line.
 *
 * Pure Kotlin (no Android): pinned in the batch-1 harness (group 20).
 */
data class DefensiveStance(
    val found: Boolean,
    val lineX: Float = 0f,
    val lineSpread: Float = 0f,
    val midfieldX: Float = 0f,
    val midfieldSpread: Float = 0f,
    val tight: Boolean = false,
    val dragOutHeld: Boolean = false
)

object DefensiveStanceProducer {

    /** D14 tight line: at most a quarter of the pitch width. */
    const val MAX_LINE_SPREAD_FRACTION = 0.25f
    /** D14 tight midfield: at most 30% of the pitch width. */
    const val MAX_MIDFIELD_SPREAD_FRACTION = 0.30f
    /** Midfield step ahead of the line, toward the ball. */
    const val MIDFIELD_AHEAD_FRACTION = 0.12f

    fun compute(
        userLine: DefensiveLineResult,
        ballX: Float,
        captureWidth: Float
    ): DefensiveStance {
        if (!userLine.found || captureWidth <= 0f) return DefensiveStance(found = false)
        val maxLineSpread = captureWidth * MAX_LINE_SPREAD_FRACTION
        val maxMidfieldSpread = captureWidth * MAX_MIDFIELD_SPREAD_FRACTION
        val rawSpread = (userLine.maxX - userLine.minX).coerceAtLeast(0f)
        val dragOutHeld = rawSpread > maxLineSpread
        val lineSpread = rawSpread.coerceAtMost(maxLineSpread)
        val lineX = userLine.averageX.coerceIn(0f, captureWidth)
        val ahead = if (ballX >= lineX) 1f else -1f
        val midfieldX = (lineX + ahead * captureWidth * MIDFIELD_AHEAD_FRACTION).coerceIn(0f, captureWidth)
        val midfieldSpread = (rawSpread * 1.2f).coerceAtMost(maxMidfieldSpread)
        return DefensiveStance(
            found = true,
            lineX = lineX,
            lineSpread = lineSpread,
            midfieldX = midfieldX,
            midfieldSpread = midfieldSpread,
            tight = true,
            dragOutHeld = dragOutHeld
        )
    }
}
