package com.assistant

object RuntimePerformanceCoordinator {

    // No68-E1-D40: masterAuthority was a plain var written from UI
    // threads (control-room sliders, ModMenu) and read on the decision
    // loop thread (RuntimeDecisionLoop) — a data race: the loop could
    // read a stale authority forever on some cores. Volatile, like the
    // state holder below.
    @Volatile
    private var masterAuthority:Int = 100

    fun updateAuthority(authority:Int){
        masterAuthority = authority.coerceIn(0,100)
    }

    fun authority():Int = masterAuthority

    // No68-E1-D41: runtimeAuthority() returned authority * 10 (0..1000)
    // and the control rooms displayed it beside a "%" as the "Runtime"
    // figure — a full-authority engine showed "1000 Runtime". No caller
    // anywhere uses the 0..1000 range; the scale was a bug, not a unit.
    // Return the same 0..100 scale as authority(). The three one-line
    // wrappers had zero callers and were deleted in Phase R (R-6).
    fun runtimeAuthority(): Int = authority().coerceIn(0,100)

    @Volatile
    private var state = RuntimePerformanceState()

    fun current(): RuntimePerformanceState = state

    fun refresh() {
        // P8: one shared refresh per cycle — the leaf refreshes below
        // used to re-run RuntimeDiagnosticsRegistry.refresh() 4x per
        // coordinator refresh. The cycle dedups them (RefreshCycle).
        val cycle = RefreshCycle.begin()
        try {
            RuntimeDiagnosticsRegistry.refresh()
            RuntimeVisualizationRegistry.refresh()
            FPSMonitor.refresh()
            ConfidenceHeatmap.refresh()

            state = RuntimePerformanceState(
                diagnostics = RuntimeDiagnosticsRegistry.current(),
                fpsMonitor = FPSMonitor.current(),
                latencyMonitor = VisionLatencyMonitor.current(),
                confidenceHeatmap = ConfidenceHeatmap.current()
            )
        } finally {
            RefreshCycle.end(cycle)
        }
    }

    fun activate() {
        RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        RuntimeVisualizationRegistry.enableVisualization()
        refresh()
    }

    // No68-E1-D42: activate() had no counterpart — once enabled, the
    // diagnostics/visualization registries could never be switched off
    // again from this coordinator (their disable*() methods exist and
    // have no other caller). Symmetric deactivate, same shape as
    // activate().
    fun deactivate() {
        RuntimeDiagnosticsRegistry.disableRuntimeDiagnostics()
        RuntimeVisualizationRegistry.disableVisualization()
        refresh()
    }

    fun reset() {
        RuntimeDiagnosticsRegistry.reset()
        RuntimeVisualizationRegistry.reset()
        refresh()
    }

    fun synchronizeExistingPerformanceEngines() {
        synchronizeRuntimePipeline()
    }

    fun synchronizeRuntimePipeline() {
        // P8: same shared cycle as refresh() (nested begin/end share
        // the outer cycle id — see RefreshCycle).
        val cycle = RefreshCycle.begin()
        try {
            RuntimeDiagnosticsRegistry.refresh()
            RuntimeVisualizationRegistry.refresh()

            FPSMonitor.refresh()
            ConfidenceHeatmap.refresh()

            refresh()
        } finally {
            RefreshCycle.end(cycle)
        }
    }

}
