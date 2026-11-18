package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























// No68-E1-D43: a state snapshot with no timestamp cannot be aged —
// a reader cannot tell a fresh refresh from a stale one left over
// from boot. Stamp construction time (wall clock, like the other
// runtime snapshots). The five *Enabled flags are capability
// advertisements, not live gates: they are always true, no setter
// exists, and no reader anywhere consults them (see defects.md
// No68-E1-D44 — wiring a real reader is queued, not invented here).
data class RuntimePerformanceState(
    val diagnostics: RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current(),
    val visualization: RuntimeVisualizationState =
        RuntimeVisualizationRegistry.current(),
    val fpsMonitor: FPSMonitorState =
        FPSMonitor.current(),
    val latencyMonitor: VisionLatencyMonitorState =
        VisionLatencyMonitor.current(),
    val confidenceHeatmap: ConfidenceHeatmapState =
        ConfidenceHeatmap.current(),
    val timestampMs: Long = System.currentTimeMillis(),
    val stutterSuppressionEnabled:Boolean = true,
    val lagCompensationEnabled:Boolean = true,
    val inputDelayReductionEnabled:Boolean = true,
    val networkLatencyReductionEnabled:Boolean = true,
    val fpsStabilizationEnabled:Boolean = true
)

