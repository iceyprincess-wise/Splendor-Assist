package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object CrossPrecisionEngine {
    fun calculate(x: Float, y: Float, strength: Int): CrossPrecisionResult {
        val out = FloatArray(3)
        NativePorts.cross(x, y, strength, out)
        // N5: the cross target is an absolute pitch point (lane receiver /
        // open-space centre / pass point). In the 2026-11-18 session it
        // was already at/below the bottom edge (y >= 633 in a 640-tall
        // capture) before arbitration added its +/-180 dy. Clamp the
        // target itself to the live capture bounds; the choke-point clamp
        // in route() is the final guarantee, this stops the target from
        // being off-screen at the source. Unset capture passes through.
        // (The port itself stays a pure formula — the clamp lives here,
        // in the engine wrapper, not in NativePorts.cross.)
        val w = com.assistant.vision.CameraProfile.captureWidth.toFloat()
        val h = com.assistant.vision.CameraProfile.captureHeight.toFloat()
        val cx = if (w > 0f && h > 0f) out[0].coerceIn(0f, w) else out[0]
        val cy = if (w > 0f && h > 0f) out[1].coerceIn(0f, h) else out[1]
        return CrossPrecisionResult(cx, cy, out[2])
    }

}

/* ========
CrossingLaneAnalysisEngine
======== */

