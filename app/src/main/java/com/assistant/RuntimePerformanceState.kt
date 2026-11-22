package com.assistant

// No68-E1-D43: a state snapshot with no timestamp cannot be aged —
// a reader cannot tell a fresh refresh from a stale one left over
// from boot. Stamp construction time (wall clock, like the other
// runtime snapshots).
// Item 13 (half-effect): the five *Enabled flags are gone — they
// were capability advertisements, always true, no setter, no reader
// (defects.md No68-E1-D44). The visualization snapshot is gone too
// (RuntimeVisualizationState deleted — write-only re-copy). The
// remaining snapshots have their first reader in DashboardInjector
// (runtime section).
data class RuntimePerformanceState(
    val diagnostics: RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current(),
    val fpsMonitor: FPSMonitorState =
        FPSMonitor.current(),
    val latencyMonitor: VisionLatencyMonitorState =
        VisionLatencyMonitor.current(),
    val confidenceHeatmap: ConfidenceHeatmapState =
        ConfidenceHeatmap.current(),
    val timestampMs: Long = System.currentTimeMillis()
)
