package com.assistant.runtime



data class MotionSample(val x: Float, val y: Float, val atNanos: Long)
data class PredictedPoint(val x: Float, val y: Float, val vxPerSecond: Float, val vyPerSecond: Float)
/** Constant velocity estimator. Time is monotonic; uncertainty is never a measured game stat. */
class PredictiveState {
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
        previous[id] = MotionSample(x, y, atNanos)
        val dt = if (old == null) 0L else atNanos - old.atNanos
        val vx = if (old != null && dt in 1_000_000L..250_000_000L) (x - old.x) * 1e9f / dt else 0f
        val vy = if (old != null && dt in 1_000_000L..250_000_000L) (y - old.y) * 1e9f / dt else 0f
        val seconds = if (leadMs.isFinite()) leadMs.coerceIn(0f, 100f) / 1000f else 0f
        return PredictedPoint(clampToCapture(x + vx * seconds, width),
            clampToCapture(y + vy * seconds, height), vx, vy)
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
