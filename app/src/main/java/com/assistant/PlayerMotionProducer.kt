package com.assistant

/**
 * B6 — Player-Motion Producer + Velocity Mapping (engine-ideas §2,
 * Q68-52 / Q58-6).
 *
 * Two halves of one bottleneck: (1) player motion never reached the
 * decision layer in px/s — TrackedPlayer.velocityX/Y is an EMA-smoothed
 * PER-FRAME displacement, not a velocity; (2) the gesture controller
 * passed opponent velocity as 0f to the native agility physics, so the
 * closing-speed dot product was computed against zero.
 *
 * TRACE: CALLER SceneTracker.update (measured inter-update dt) → ENGINE
 * this producer (per-frame displacement → px/s) → CALLEE none (pure
 * math) → DATA FLOW px/s per tracked player → DECISION the px/s→
 * normalised mapping below → MUTATION TrackedPlayer.velocityXPerSecond/Y
 * + RuntimeEntity.velocityXPerSecond/Y → CONSUMER ActiveGestureController
 * (native agility oppVx/oppVy) + the contributors that read velocity →
 * LIVE EFFECT the closing-speed dot product is computed against the
 * opponent's real motion instead of zero.
 *
 * The conversion is MEASURED, never derived from FPS: the caller passes
 * the real elapsed time between updates. A dt outside 8..250 ms (a
 * stall, a clock jump, the first update) yields 0 — no motion is
 * claimed — instead of scaling a displacement by a guessed rate.
 *
 * The normalised mapping is the AGC call site's own 0..15 velocity
 * space (the same space agilityVelocityUnits already uses for the
 * carrier's own motion). The anchor is 500 px/s = full scale: a player
 * crossing the 640 px capture height in ~1.3 s. That is a stated
 * anchor, not a measured sprint speed — it only sets the scale, and the
 * direction is preserved exactly (per-component mapping, not magnitude).
 */
object PlayerMotionProducer {

    /** px/s that maps to the top of the normalised 0..15 space. See KDoc. */
    const val FULL_SCALE_PX_PER_S = 500f

    /** Top of the AGC normalised velocity space. */
    const val NORMALISED_MAX = 15f

    /** Shortest believable inter-update gap (one 120 Hz frame). */
    const val MIN_DT_MS = 8L

    /** Longest believable inter-update gap; beyond it the track is stale. */
    const val MAX_DT_MS = 250L

    /**
     * Per-frame displacement → px/s over the MEASURED gap. Returns 0f
     * when the gap is not a believable frame interval (never a guessed
     * fps conversion).
     */
    fun perSecond(perFrameDisplacement: Float, dtMs: Long): Float {
        if (!perFrameDisplacement.isFinite()) return 0f
        if (dtMs < MIN_DT_MS || dtMs > MAX_DT_MS) return 0f
        return (perFrameDisplacement * 1000f / dtMs.toFloat()).coerceIn(-5000f, 5000f)
    }

    /**
     * px/s → the AGC normalised 0..15 space, per component so the
     * direction is preserved. 500 px/s maps to 15; faster saturates.
     */
    fun toNormalised(vxPerSecond: Float, vyPerSecond: Float): Pair<Float, Float> {
        val scale = NORMALISED_MAX / FULL_SCALE_PX_PER_S
        val nx = if (vxPerSecond.isFinite()) (vxPerSecond * scale).coerceIn(-NORMALISED_MAX, NORMALISED_MAX) else 0f
        val ny = if (vyPerSecond.isFinite()) (vyPerSecond * scale).coerceIn(-NORMALISED_MAX, NORMALISED_MAX) else 0f
        return Pair(nx, ny)
    }
}
