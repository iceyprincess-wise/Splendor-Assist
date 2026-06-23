package com.assistant.overlay.interceptor

import kotlin.math.abs
import kotlin.math.atan2

object NearPostCoverageEngine {

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

        val disparity =
            abs(
                Math.toDegrees(
                    angleToPost - angleToGK
                )
            )

        return disparity > 25.0
    }
}
