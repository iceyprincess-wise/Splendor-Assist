package com.assistant.adapter.smartassist

import com.assistant.adapter.smartassist.fps.FrameDropStabilizer
import com.assistant.adapter.smartassist.fps.LatencyDefeatingInputEngine
import com.assistant.adapter.smartassist.fps.MemoryStabilityOptimizer
import com.assistant.adapter.smartassist.fps.VsyncInputAnchor

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

    private var masterAuthority:Int = 100

    fun updateAuthority(authority:Int){
        masterAuthority = authority.coerceIn(0,100)
    }

    fun authority():Int = masterAuthority

    fun runtimeAuthority(): Int = authority().coerceIn(0,100) * 10

    fun goalkeeperAuthority(): Int = runtimeAuthority()

    fun interceptionAuthority(): Int = runtimeAuthority()

    fun smartAssistAuthority(): Int = runtimeAuthority()





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

    

    // PHASE9_EXISTING_ENGINE_INTEGRATION_MARKER

    fun synchronizeExistingPerformanceEngines() {

        /*
         * Existing performance engines are preserved.
         * Integration remains indirect until concrete module-visible
         * APIs are audited. This avoids introducing unresolved
         * cross-module references while keeping the orchestration
         * entry point stable.
         */

        synchronizeRuntimePipeline()
    }



// PHASE9_RUNTIME_PERFORMANCE_ORCHESTRATION_MARKER

    fun synchronizeRuntimePipeline() {

        RuntimeDiagnosticsRegistry.refresh()
        RuntimeVisualizationRegistry.refresh()

        FPSMonitor.refresh()
        VisionLatencyMonitor.refresh()
        ConfidenceHeatmap.refresh()

        refresh()
    }

}
