package com.assistant

object VisionLatencyMonitor {

    @Volatile
    private var state = VisionLatencyMonitorState()

    fun current(): VisionLatencyMonitorState = state

    // No68-E2-D19: guard + staleness. update() HAS an in-repo producer:
    // VisionCore.reportVisionLatency() calls it on every process() call
    // with the measured wall time (B7) — latencyMs stays 0f and
    // hasMeasurement stays false only until the first frame is processed,
    // and 0 must never read as "0 ms latency". Non-finite / negative
    // readings are ignored, keeping the last good measurement instead of
    // poisoning the state.
    @Synchronized
    fun update(latencyMs: Float) {
        if (!latencyMs.isFinite() || latencyMs < 0f) return
        state = state.copy(
            latencyMs = latencyMs,
            hasMeasurement = true,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    // Item 13 (half-effect): refresh() is gone — its only work was
    // copying the dead `enabled` / `diagnostics` fields (deleted with
    // VisionLatencyMonitorState's re-copies). The latency state needs no
    // refresh: update() is the only writer and it writes unconditionally.
}
