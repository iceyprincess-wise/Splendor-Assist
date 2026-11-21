package com.assistant.runtime



data class MotionSample(
    val x: Float,
    val y: Float,
    val atNanos: Long,
    // Queue item 22: the velocity computed for THIS sample, so the next
    // project() call can test whether the entity turned since (angle
    // between consecutive velocity vectors). 0f on the first sample —
    // no turn can be established yet, and the speed floor below treats
    // a zero previous velocity as straight.
    val vxPerSecond: Float = 0f,
    val vyPerSecond: Float = 0f
)
data class PredictedPoint(val x: Float, val y: Float, val vxPerSecond: Float, val vyPerSecond: Float)
/** Constant velocity estimator. Time is monotonic; uncertainty is never a measured game stat. */
class PredictiveState {
    companion object {
        // Queue item 22 (leadMs loophole fix, Ed's decision 2026-11-21):
        // the horizon stays 100 ms by default and extends to 200 ms ONLY
        // for a straight-moving entity on a late frame. BASE_CAP_MS is
        // the Ed-set 100 ms cap (No68-C-D15) — NOT re-tuned. EXTENDED_CAP_MS
        // is the extension ceiling Ed authorised for this item only.
        const val BASE_CAP_MS = 100f
        const val EXTENDED_CAP_MS = 200f
        // A sharp turn at the 20 fps processed rate changes heading by
        // 45–90°+ within one 50 ms frame; straight-run tracking noise is
        // ~11–22° per frame. 45° sits above the noise band and below the
        // canonical 90° cut. TURN_COS is cos(45°).
        const val TURN_ANGLE_DEGREES = 45f
        const val TURN_COS = 0.70710678f
        // Both velocity vectors must be at least this fast for their
        // angle to mean anything: 2 px/frame at 20 fps — the SAME floor
        // as the ball's deflection rule (MotionTracker
        // DEFLECTION_MIN_SPEED_PX_PER_FRAME = 2f). Below it, direction
        // is tracking noise, so no turn is established.
        const val MIN_TURN_SPEED_PX_PER_SEC = 40f
    }
    private val previous = mutableMapOf<Int, MotionSample>()
    // PS-D3: project/retain/reset share this monitor. The only production
    // caller (FrameAssembler) runs on visionScope (Dispatchers.Default) with
    // one launch per captured image, so frames can overlap; a plain HashMap
    // must never be read and written from two threads at once.
    @Synchronized fun project(id: Int, x: Float, y: Float, atNanos: Long, leadMs: Float,
        width: Float, height: Float): PredictedPoint {
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0 || height <= 0)
            return PredictedPoint(clampToCapture(x, width), clampToCapture(y, height), 0f, 0f)
        val old = previous[id]
        if (old != null && atNanos <= old.atNanos) return PredictedPoint(clampToCapture(x, width), clampToCapture(y, height), 0f, 0f)
        val dt = if (old == null) 0L else atNanos - old.atNanos
        val vx = if (old != null && dt in 1_000_000L..250_000_000L) (x - old.x) * 1e9f / dt else 0f
        val vy = if (old != null && dt in 1_000_000L..250_000_000L) (y - old.y) * 1e9f / dt else 0f
        // Queue item 22: leadMs is the REQUESTED horizon (FrameAssembler
        // passes the uncapped age + 50 ms, capped at EXTENDED_CAP_MS).
        // The effective horizon is per-entity: a turning entity is capped
        // at BASE_CAP_MS (today's behaviour, unchanged); a straight-moving
        // entity may use the full requested horizon up to EXTENDED_CAP_MS.
        // The turn test compares the new velocity against the previous
        // sample's stored velocity — one angle, no deeper history.
        val turning = isTurning(old?.vxPerSecond ?: 0f, old?.vyPerSecond ?: 0f, vx, vy)
        val capMs = if (turning) BASE_CAP_MS else EXTENDED_CAP_MS
        val seconds = if (leadMs.isFinite()) leadMs.coerceIn(0f, capMs) / 1000f else 0f
        previous[id] = MotionSample(x, y, atNanos, vx, vy)
        return PredictedPoint(clampToCapture(x + vx * seconds, width),
            clampToCapture(y + vy * seconds, height), vx, vy)
    }
    /**
     * Queue item 22: true when the entity turned sharply between the
     * previous velocity and the new one — angle >= TURN_ANGLE_DEGREES
     * with both speeds above the noise floor. Below the floor no turn
     * can be established (direction is tracking noise) and the entity
     * counts as straight; its displacement is tiny either way.
     */
    private fun isTurning(oldVx: Float, oldVy: Float, vx: Float, vy: Float): Boolean {
        val oldSpeed = kotlin.math.hypot(oldVx, oldVy)
        val newSpeed = kotlin.math.hypot(vx, vy)
        if (!oldSpeed.isFinite() || !newSpeed.isFinite()) return false
        if (oldSpeed < MIN_TURN_SPEED_PX_PER_SEC || newSpeed < MIN_TURN_SPEED_PX_PER_SEC) return false
        val cosAngle = (oldVx * vx + oldVy * vy) / (oldSpeed * newSpeed)
        if (!cosAngle.isFinite()) return false
        return cosAngle < TURN_COS
    }
    @Synchronized fun retain(ids: Set<Int>) { previous.keys.retainAll(ids) }
    @Synchronized fun reset() { previous.clear() }
    /**
     * PS-D1/PS-D2: every returned point stays inside the capture, including
     * the early returns. A sub-pixel capture (0 < size < 1) has no interior
     * pixel, so its only valid coordinate is 0f; an invalid size leaves the
     * value untouched instead of throwing on an empty coerce range. NaN
     * passes through unchanged - coerceIn never invents a position for it.
     */
    private fun clampToCapture(value: Float, size: Float): Float {
        if (!size.isFinite() || size <= 0f) return value
        return value.coerceIn(0f, (size - 1f).coerceAtLeast(0f))
    }
}
