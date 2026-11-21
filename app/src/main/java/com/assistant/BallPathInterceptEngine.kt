package com.assistant






































import kotlin.math.*




























/*
 * Ball-path intercept geometry (queue item 11, F2) — pure.
 *
 * When a pass is IN FLIGHT, the defender should cut the BALL's path,
 * not chase the receiver: the closest point on the ball's velocity ray
 * to the controlled defender, clamped to the segment the prediction
 * covers. The caller (InstantInterceptContributor) decides WHEN to cut
 * (native predicted distance > the 60 px possession rule) and falls
 * back to pressing the owner when this returns null; this file only
 * answers WHERE the cut is, so the geometry is harness-testable
 * without the native library (which never loads on the JVM).
 *
 * Units: the ball velocity is px per FRAME, exactly as
 * BallTrajectoryPredictor.current() publishes it (MotionTracker
 * position deltas, no dt division). The projection parameter t is
 * therefore in FRAMES, and the segment is [current, predicted] — the
 * 6-frame point the predictor publishes.
 */
object BallPathInterceptEngine {

    // The ball's velocity ray is only meaningful when the ball is really
    // moving: below 2 px/frame the velocity is jitter, not a line. 2f is
    // MotionTracker's deflection floor
    // (DEFLECTION_MIN_SPEED_PX_PER_FRAME) — cited, not re-tuned; the
    // constant itself stays private in MotionTracker, so the value is
    // stated here with its provenance, not invented as a new threshold.
    const val MIN_BALL_SPEED_PX_PER_FRAME = 2f

    // The prediction horizon, in frames — the same 6-frame horizon
    // MotionTracker and the keeper predictor use
    // (LOOK_AHEAD_FRAMES = 6f). The cut is only offered inside the
    // known prediction; beyond it the ball may deflect or turn.
    const val LOOK_AHEAD_FRAMES = 6f

    /**
     * Closest point on the ball's velocity ray to
     * (defenderX, defenderY), or null when there is no usable cut:
     * ball too slow (below the deflection floor), or the projection
     * falls outside the [current, predicted] segment (behind the ball
     * = the pass has already gone past; beyond the 6-frame point =
     * outside the known prediction).
     */
    fun interceptPoint(
        ballX: Float, ballY: Float,
        ballVxPerFrame: Float, ballVyPerFrame: Float,
        defenderX: Float, defenderY: Float
    ): Pair<Float, Float>? {
        val speed = hypot(ballVxPerFrame.toDouble(), ballVyPerFrame.toDouble()).toFloat()
        if (speed < MIN_BALL_SPEED_PX_PER_FRAME) return null
        val speedSq = ballVxPerFrame * ballVxPerFrame + ballVyPerFrame * ballVyPerFrame
        val t = ((defenderX - ballX) * ballVxPerFrame +
            (defenderY - ballY) * ballVyPerFrame) / speedSq
        if (!t.isFinite() || t < 0f || t > LOOK_AHEAD_FRAMES) return null
        return Pair(ballX + ballVxPerFrame * t, ballY + ballVyPerFrame * t)
    }
}
