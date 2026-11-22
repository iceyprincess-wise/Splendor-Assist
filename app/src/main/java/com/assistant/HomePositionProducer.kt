package com.assistant

/**
 * L14 — Home Position Producer (HomePositionProducer).
 *
 * Gap (Q68-92): no producer for a defender's real home position — screen
 * centre stood in (DefenseContributor passed screenW * 0.5f /
 * screenH * 0.5f as the stopper home anchor, QH-6).
 *
 * Contract: in = formation + defender identity; out = home position per
 * defender; caller = VisionCore (for the tracked defender on the frame —
 * batch 6d: the old doc said FrameAssembler, but the live call site is
 * VisionCore:320); consumer = DefenseContributor (the stopper matrix home
 * anchor).
 *
 * The home is "return to the tight line in your own lateral lane": the
 * X is the user line's average (clamped to the line's own min/max, so a
 * defender never goes home outside the line it belongs to); the Y is the
 * defender's own lateral position (their lane). FormationResult today is
 * name-only (no per-slot geometry), so no per-slot Y exists to use — the
 * Y is preserved honestly rather than invented from a formation name.
 * When no user line is found the producer reports found=false and the
 * caller keeps the historical screen-centre fallback.
 *
 * Pure Kotlin (no Android): pinned in the batch-1 harness (group 20).
 */
data class HomePosition(
    val found: Boolean,
    val x: Float = 0f,
    val y: Float = 0f
)

object HomePositionProducer {

    fun homePosition(
        defenderY: Float,
        userLine: DefensiveLineResult,
        captureWidth: Float,
        captureHeight: Float
    ): HomePosition {
        if (!userLine.found || captureWidth <= 0f || captureHeight <= 0f) {
            return HomePosition(found = false)
        }
        // Phase H (H-1): guard the coerceIn precondition before it.
        // Float.coerceIn(min, max) THROWS when min > max, and that state
        // is reachable by construction: DefensiveLineEngine starts
        // minX=+Inf / maxX=-Inf and folds a player in only when
        // player.x < minX / player.x > maxX — a NaN x fails both, so if
        // EVERY kept player's x is NaN the line reports found=true with
        // minX=+Inf, maxX=-Inf, and the throw lands inside VisionCore's
        // per-frame compute. With >=1 real player min <= max always, so
        // this is behaviour-identical for every real input. (The finite
        // check below catches a NaN *average*, but it never runs when
        // coerceIn throws first.)
        if (userLine.minX > userLine.maxX) {
            return HomePosition(found = false)
        }
        val homeX = userLine.averageX.coerceIn(userLine.minX, userLine.maxX)
            .coerceIn(0f, captureWidth)
        val homeY = defenderY.coerceIn(0f, captureHeight)
        if (!homeX.isFinite() || !homeY.isFinite()) return HomePosition(found = false)
        return HomePosition(found = true, x = homeX, y = homeY)
    }
}
