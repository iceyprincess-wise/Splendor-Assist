package com.assistant.runtime

import kotlin.math.hypot

data class MotionSample(val x: Float, val y: Float, val atNanos: Long)
data class PredictedPoint(val x: Float, val y: Float, val vxPerSecond: Float, val vyPerSecond: Float)
/** Constant velocity estimator. Time is monotonic; uncertainty is never a measured game stat. */
class PredictiveState {
    private val previous = mutableMapOf<Int, MotionSample>()
    fun project(id: Int, x: Float, y: Float, atNanos: Long, leadMs: Float,
        width: Float, height: Float): PredictedPoint {
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0 || height <= 0)
            return PredictedPoint(x, y, 0f, 0f)
        val old = previous[id]
        if (old != null && atNanos <= old.atNanos) return PredictedPoint(x, y, 0f, 0f)
        previous[id] = MotionSample(x, y, atNanos)
        val dt = if (old == null) 0L else atNanos - old.atNanos
        val vx = if (old != null && dt in 1_000_000L..250_000_000L) (x - old.x) * 1e9f / dt else 0f
        val vy = if (old != null && dt in 1_000_000L..250_000_000L) (y - old.y) * 1e9f / dt else 0f
        val seconds = if (leadMs.isFinite()) leadMs.coerceIn(0f, 100f) / 1000f else 0f
        return PredictedPoint((x + vx * seconds).coerceIn(0f, width - 1f),
            (y + vy * seconds).coerceIn(0f, height - 1f), vx, vy)
    }
    fun retain(ids: Set<Int>) { previous.keys.retainAll(ids) }
    fun reset() { previous.clear() }
}
