from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

f = PKG / "RuntimePerformanceCoordinator.kt"

f.write_text("""package com.assistant.adapter.smartassist

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

object RuntimePerformanceCoordinator {

    @Volatile
    private var state = RuntimePerformanceState()

    fun current(): RuntimePerformanceState = state

    fun refresh() {
        RuntimeDiagnosticsRegistry.refresh()
        RuntimeVisualizationRegistry.refresh()
        FPSMonitor.refresh()
        VisionLatencyMonitor.refresh()
        ConfidenceHeatmap.refresh()

        state = RuntimePerformanceState(
            diagnostics = RuntimeDiagnosticsRegistry.current(),
            visualization = RuntimeVisualizationRegistry.current(),
            fpsMonitor = FPSMonitor.current(),
            latencyMonitor = VisionLatencyMonitor.current(),
            confidenceHeatmap = ConfidenceHeatmap.current(),
            stutterSuppressionEnabled = true,
            lagCompensationEnabled = true,
            inputDelayReductionEnabled = true,
            networkLatencyReductionEnabled = true,
            fpsStabilizationEnabled = true
        )
    }

    fun activate() {
        RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        RuntimeVisualizationRegistry.enableVisualization()
        refresh()
    }

    fun reset() {
        RuntimeDiagnosticsRegistry.reset()
        RuntimeVisualizationRegistry.reset()
        refresh()
    }
}
""", encoding="utf-8")

print("CREATED:")
print(f)
