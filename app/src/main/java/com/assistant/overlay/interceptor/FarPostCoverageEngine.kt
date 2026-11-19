package com.assistant.overlay.interceptor

import com.assistant.GoalPostCalibration

object FarPostCoverageEngine {

    fun shouldCover(
        decision: ThreatDecision
    ): Boolean {

        return decision.direction ==
            ShotDirection.FAR_POST
    }

    /**
     * L2 (batch 1): the anchored overload. A far-post coverage claim is
     * only meaningful against anchored posts (GoalPostCalibrationEngine):
     * with no valid calibration there is no far post to cover, so the
     * claim reads false instead of guessing from the direction alone.
     * The direction-only overload above stays for callers without a
     * calibration. The router passes latest(), so the GK_DECISION line's
     * FarPostCoverageEngine value is now anchored truth, not a guess.
     */
    fun shouldCover(
        decision: ThreatDecision,
        calibration: GoalPostCalibration
    ): Boolean {
        if (decision.direction != ShotDirection.FAR_POST) return false
        return calibration.valid
    }
}
