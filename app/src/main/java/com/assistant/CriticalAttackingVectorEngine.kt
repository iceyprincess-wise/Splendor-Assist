package com.assistant














import android.graphics.PointF























import com.assistant.runtime.*


























import kotlin.math.*




























object CriticalAttackingVectorEngine {
    private val outScoring = FloatArray(2)
    private val outPass = FloatArray(2)

    fun computeAbsoluteScoringVector(strikerX: Float, strikerY: Float, gkX: Float, gkY: Float, goalLeftPostX: Float, goalLeftPostY: Float, goalRightPostX: Float, goalRightPostY: Float): PointF {
        // The three trailing control args are deleted, not defaulted: both
        // live callers passed only the 8 positional args, so the only
        // values ever supplied were the 0f defaults. The 0f literals go
        // straight to the native call, so the computed vector is
        // bit-identical. (Consequence, QUEUED not fixed here: with a
        // zero radius the native formula returns (0,0) on every call —
        // see TRACE-B3 queue item Q-C3-1.)
        com.assistant.NativeBridge.nativeCriticalScoringVector(strikerX, strikerY, gkX, gkY, goalLeftPostX, goalLeftPostY, goalRightPostX, goalRightPostY, 0f, 0f, 0f, outScoring)
        return PointF(outScoring[0], outScoring[1])
    }

    fun computeTrueTargetPass(passButtonX: Float, passButtonY: Float, activeStrikerX: Float, activeStrikerY: Float, strikerVx: Float, strikerVy: Float, isLoftedContext: Boolean): PointF {
        // Screen-size defaults deleted: the only caller passed 7 args, so
        // the defaults were the only values ever supplied. The profile
        // fallbacks are read directly inside now — same values, same call.
        com.assistant.NativeBridge.nativeCriticalTrueTargetPass(passButtonX, passButtonY, activeStrikerX, activeStrikerY, strikerVx, strikerVy, isLoftedContext, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), outPass)
        return PointF(outPass[0], outPass[1])
    }
}

/* ========
CrossPrecisionEngine
======== */
