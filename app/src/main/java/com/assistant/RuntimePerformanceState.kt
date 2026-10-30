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
    val stutterSuppressionEnabled:Boolean = true,
    val lagCompensationEnabled:Boolean = true,
    val inputDelayReductionEnabled:Boolean = true,
    val networkLatencyReductionEnabled:Boolean = true,
    val fpsStabilizationEnabled:Boolean = true
)

