package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object WingBlockEngine {

    // Queue item 10 (F1): the flank bands were tested on X — the
    // GOAL-TO-GOAL axis (goals sit at the X ends, AttackAxisModel) — so
    // the "wing block" fired near the goal lines and offered nothing on
    // a genuine flank. Flanks are the Y extremes (same bands
    // WingOverloadDetectionEngine uses: y < h/3, y > 2h/3 for its
    // thirds; the block itself works the outer 15% touchline strips).
    // Same fractions as before (0.15/0.85 bands, 0.12/0.88 anchors,
    // 0.2f lead), correct axis. The 0.2f factor is now a real 200 ms
    // lead: callers pass PER-SECOND velocity (lateness batch).
    // pitchWidth is gone from the signature: nothing on the goal-to-goal
    // axis decides a flank any more; X follows the winger.
    fun calculateWingBlockVector(
        wingerX: Float,
        wingerY: Float,
        wingerVx: Float,
        wingerVy: Float,
        pitchHeight: Float
    ): WingBlockResult? {

        val topBoundary  = pitchHeight * 0.15f
        val bottomBoundary = pitchHeight * 0.85f

        val isTopFlank  = wingerY < topBoundary
        val isBottomFlank = wingerY > bottomBoundary

        // Not a wing situation: return null so WingBlockContributor skips.
        // Previously returned ball position (non-null), firing a redundant
        // central press that duplicated DefenseContributor with no added value.
        if (!isTopFlank && !isBottomFlank) return null

        val baseAnchorY = if (isTopFlank) pitchHeight * 0.12f else pitchHeight * 0.88f
        val blockingAnchorX = wingerX + (wingerVx * 0.2f)
        val blockingAnchorY = (baseAnchorY + (wingerVy * 0.2f))

        return WingBlockResult(blockingAnchorX, blockingAnchorY)
    }
}

/* ========
WingOverloadDetectionResult
======== */
