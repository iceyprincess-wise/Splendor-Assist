package com.assistant.adapter.smartassist

import android.graphics.PointF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * [PRIME AUTHORITATIVE ENGINE] — Omnipotent Scoring & True Corridor Alignment
 * Upgraded from raw field patch to convert goal coordinates into virtual control vectors and clamp velocity drift.
 */
@Suppress("UNUSED_PARAMETER")
object CriticalAttackingVectorEngine {

    fun computeAbsoluteScoringVector(
        strikerX: Float, strikerY: Float,
        gkX: Float, gkY: Float,
        goalLeftPostX: Float, goalLeftPostY: Float,
        goalRightPostX: Float, goalRightPostY: Float,
        controlOriginX: Float = 1400f,
        controlOriginY: Float = 550f,
        controlRadius: Float = 110f
    ): PointF {
        val gkDistToLeft = hypot((goalLeftPostX - gkX).toDouble(), (goalLeftPostY - gkY).toDouble())
        val gkDistToRight = hypot((goalRightPostX - gkX).toDouble(), (goalRightPostY - gkY).toDouble())

        val targetPostX = if (gkDistToLeft > gkDistToRight) goalLeftPostX + 35f else goalRightPostX - 35f
        val targetPostY = if (gkDistToLeft > gkDistToRight) goalLeftPostY + 15f else goalRightPostY + 15f

        val firingAngle = atan2(
            (targetPostY - strikerY).toDouble(),
            (targetPostX - strikerX).toDouble()
        )

        val swipeTargetX = controlOriginX + (cos(firingAngle) * controlRadius).toFloat()
        val swipeTargetY = controlOriginY + (sin(firingAngle) * controlRadius).toFloat()

        return PointF(swipeTargetX, swipeTargetY)
    }

    fun computeTrueTargetPass(
        passButtonX: Float, passButtonY: Float,
        activeStrikerX: Float, activeStrikerY: Float,
        strikerVx: Float, strikerVy: Float,
        isLoftedContext: Boolean,
        screenWidth: Float = 1650f,
        screenHeight: Float = 720f
    ): PointF {
        val velocityMagnitude = hypot(strikerVx.toDouble(), strikerVy.toDouble()).toFloat()
        val normalizedLead = if (velocityMagnitude > 1f) 18f else 180f

        val destinationX = (activeStrikerX + (strikerVx * normalizedLead)).coerceIn(100f, screenWidth - 100f)
        val destinationY = (activeStrikerY + (strikerVy * normalizedLead)).coerceIn(100f, screenHeight - 100f)

        return PointF(destinationX, destinationY)
    }
}
