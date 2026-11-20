package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























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

    // No68-E2-D20: refresh() no longer re-runs the diagnostics-registry
    // refresh — the full overlay/tuning refresh chain. All three call sites
    // (RuntimePerformanceCoordinator.refresh,
    // synchronizeRuntimePipeline, MainActivity
    // .synchronizeApplicationRuntime) already refresh diagnostics first,
    // so this was a redundant second (on the MainActivity path third)
    // full refresh on every read.
    // No68-E2-D21: update() and refresh() are @Synchronized so a refresh
    // can no longer overwrite a concurrent latency write with the stale
    // value it copied from.
    @Synchronized
    fun refresh() {
        state = state.copy(
            enabled = VisionConfigurationEngine.current().latencyMonitoringEnabled,
            diagnostics = RuntimeDiagnosticsRegistry.current()
        )
    }
}

/* ========
VisionOverlayRegistry
======== */
