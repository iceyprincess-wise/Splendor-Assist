package com.assistant.overlay.interceptor

import com.assistant.GoalPostCalibration

object NearPostCoverageEngine {

    /**
     * L2 (batch 1): the anchored overload — same rule as
     * FarPostCoverageEngine: no valid calibration, no post to cover.
     */
    fun shouldCover(
        decision: ThreatDecision,
        calibration: GoalPostCalibration
    ): Boolean {
        if (decision.direction != ShotDirection.NEAR_POST) return false
        return calibration.valid
    }
}
