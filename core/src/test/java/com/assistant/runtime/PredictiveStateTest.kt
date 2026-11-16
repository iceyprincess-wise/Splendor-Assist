package com.assistant.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PS-D1/PS-D2 pins: every point PredictiveState returns stays inside the
 * capture, including the early returns, and a sub-pixel capture never
 * throws. The 28 PredictionPolicyTest pins still guard the velocity
 * behaviour.
 */
class PredictiveStateTest {

    @Test fun guardPathClampsOutOfRangeCoordinate() {
        val p = PredictiveState()
        // y is NaN, so the guard fires; x must still be clamped to the capture.
        val r = p.project(1, 2000f, Float.NaN, 1_000_000_000L, 50f, 640f, 288f)
        assertEquals(639f, r.x, 0f)
        assertTrue(r.y.isNaN())
        assertEquals(0f, r.vxPerSecond, 0f)
    }

    @Test fun guardPathLeavesValueUntouchedWhenSizeIsInvalid() {
        val p = PredictiveState()
        // Width is NaN: there is no capture to clamp into, so x echoes raw.
        val r = p.project(1, 2000f, 10f, 1_000_000_000L, 50f, Float.NaN, 288f)
        assertEquals(2000f, r.x, 0f)
        assertEquals(10f, r.y, 0f)
    }

    @Test fun staleSampleEchoIsClamped() {
        val p = PredictiveState()
        p.project(1, 10f, 10f, 1_000_000_000L, 50f, 640f, 288f)
        val r = p.project(1, 2000f, 10f, 1_000_000_000L, 50f, 640f, 288f)
        assertEquals(0f, r.vxPerSecond, 0f)
        assertEquals(639f, r.x, 0f)
    }

    @Test fun subPixelCaptureDoesNotThrowAndClampsToZero() {
        val p = PredictiveState()
        val r = p.project(1, 2000f, 2000f, 1_000_000_000L, 50f, 0.5f, 0.5f)
        assertEquals(0f, r.x, 0f)
        assertEquals(0f, r.y, 0f)
        assertEquals(0f, r.vxPerSecond, 0f)
    }
}
