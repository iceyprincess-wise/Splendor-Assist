package com.assistant














import android.graphics.PointF























import com.assistant.runtime.*


























import kotlin.math.*




























object CriticalAttackingVectorEngine {
    private val outScoring = FloatArray(2)
    private val outPass = FloatArray(2)

    fun computeAbsoluteScoringVector(strikerX: Float, strikerY: Float, gkX: Float, gkY: Float, goalLeftPostX: Float, goalLeftPostY: Float, goalRightPostX: Float, goalRightPostY: Float): PointF {
        // Q-C3-1 FIX (was: 0f, 0f, 0f as control origin/radius, so the
        // native formula returned (0,0) on every call — see TRACE-B3 queue
        // item Q-C3-1). The control origin is now the striker and the
        // radius is the distance striker -> far inset corner (via
        // ShotTargetMath, same pick rule as the native), so the native
        // returns exactly the far inset corner by construction: the firing
        // angle points striker -> corner, and origin + unit(angle) * radius
        // = corner. JNI signature unchanged (ABI).
        val target = ShotTargetMath.farCornerTarget(gkX, gkY, goalLeftPostX, goalLeftPostY, goalRightPostX, goalRightPostY)
        val radius = ShotTargetMath.scoringRadius(strikerX, strikerY, target.x, target.y)
        // NATIVE-GUARD: the native returns the far inset corner by construction,
        // so when it is unavailable the pure-Kotlin corner is the exact neutral.
        if (!com.assistant.NativeBridge.isNativeReady()) return PointF(target.x, target.y)
        try {
            com.assistant.NativeBridge.nativeCriticalScoringVector(strikerX, strikerY, gkX, gkY, goalLeftPostX, goalLeftPostY, goalRightPostX, goalRightPostY, strikerX, strikerY, radius, outScoring)
        } catch (t: Throwable) {
            return PointF(target.x, target.y)
        }
        return PointF(outScoring[0], outScoring[1])
    }

    fun computeTrueTargetPass(passButtonX: Float, passButtonY: Float, activeStrikerX: Float, activeStrikerY: Float, strikerVx: Float, strikerVy: Float, isLoftedContext: Boolean): PointF {
        // Screen-size defaults deleted: the only caller passed 7 args, so
        // the defaults were the only values ever supplied. The profile
        // fallbacks are read directly inside now — same values, same call.
        // NATIVE-GUARD: without the native lead calculator, pass to the
        // receiver's current position (no lead) instead of throwing.
        if (!com.assistant.NativeBridge.isNativeReady()) return PointF(activeStrikerX, activeStrikerY)
        try {
            com.assistant.NativeBridge.nativeCriticalTrueTargetPass(passButtonX, passButtonY, activeStrikerX, activeStrikerY, strikerVx, strikerVy, isLoftedContext, com.assistant.vision.CameraProfile.captureWidthOrFallback(), com.assistant.vision.CameraProfile.captureHeightOrFallback(), outPass)
        } catch (t: Throwable) {
            return PointF(activeStrikerX, activeStrikerY)
        }
        return PointF(outPass[0], outPass[1])
    }
}

/* ========
CrossPrecisionEngine
======== */
