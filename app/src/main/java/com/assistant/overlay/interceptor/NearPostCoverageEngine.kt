package com.assistant.overlay.interceptor

import kotlin.math.abs
import kotlin.math.atan2

object NearPostCoverageEngine {

    // No65-D27: the near-post exposure threshold, named. UNTUNED
    // placeholder — no tuning reference exists anywhere in the repo; 25.0
    // was a bare magic number in the disparity check below.
    private const val NEAR_POST_VULNERABILITY_DEGREES = 25.0

    fun shouldCover(
        decision: ThreatDecision
    ): Boolean {

        return decision.direction ==
            ShotDirection.NEAR_POST
    }

    fun checkNearPostVulnerability(
        strikerX: Float,
        strikerY: Float,
        nearPostX: Float,
        nearPostY: Float,
        goalkeeperX: Float,
        goalkeeperY: Float
    ): Boolean {

        val angleToPost =
            atan2(
                (nearPostY - strikerY).toDouble(),
                (nearPostX - strikerX).toDouble()
            )

        val angleToGK =
            atan2(
                (goalkeeperY - strikerY).toDouble(),
                (goalkeeperX - strikerX).toDouble()
            )

        val rawDisparity =
            abs(
                Math.toDegrees(
                    angleToPost - angleToGK
                )
            )

        // No65-D21: wrap-around — angles straddling the ±π branch cut read
        // ~360° instead of ~0° (e.g. −179.4° vs +179.4° read as 358.9°
        // apart when the true separation is 1.1°), so a straddle always
        // returned TRUE. The true separation is min(d, 360 − d).
        val disparity =
            minOf(rawDisparity, 360.0 - rawDisparity)

        return disparity > NEAR_POST_VULNERABILITY_DEGREES
    }
}
