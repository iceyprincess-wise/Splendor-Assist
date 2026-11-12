package com.assistant














import android.graphics.PointF























import com.assistant.runtime.*


























import kotlin.math.*




























object CriticalAttackingVectorEngine {
    private val outScoring = FloatArray(2)
    private val outPass = FloatArray(2)

    fun computeAbsoluteScoringVector(strikerX: Float, strikerY: Float, gkX: Float, gkY: Float, goalLeftPostX: Float, goalLeftPostY: Float, goalRightPostX: Float, goalRightPostY: Float, controlOriginX: Float = 0f, controlOriginY: Float = 0f, controlRadius: Float = 0f): PointF {
        com.assistant.NativeBridge.nativeCriticalScoringVector(strikerX, strikerY, gkX, gkY, goalLeftPostX, goalLeftPostY, goalRightPostX, goalRightPostY, controlOriginX, controlOriginY, controlRadius, outScoring)
        return PointF(outScoring[0], outScoring[1])
    }

    fun computeTrueTargetPass(passButtonX: Float, passButtonY: Float, activeStrikerX: Float, activeStrikerY: Float, strikerVx: Float, strikerVy: Float, isLoftedContext: Boolean, screenWidth: Float = com.assistant.vision.CameraProfile.captureWidthOrFallback(), screenHeight: Float = com.assistant.vision.CameraProfile.captureHeightOrFallback()): PointF {
        com.assistant.NativeBridge.nativeCriticalTrueTargetPass(passButtonX, passButtonY, activeStrikerX, activeStrikerY, strikerVx, strikerVy, isLoftedContext, screenWidth, screenHeight, outPass)
        return PointF(outPass[0], outPass[1])
    }
}

/* ========
CrossPrecisionEngine
======== */
