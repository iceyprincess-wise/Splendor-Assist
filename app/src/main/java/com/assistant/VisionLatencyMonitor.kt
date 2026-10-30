object VisionLatencyMonitor {

    @Volatile
    private var state = VisionLatencyMonitorState()

    fun current():VisionLatencyMonitorState = state

    fun update(latencyMs:Float){
        state = state.copy(latencyMs = latencyMs)
    }

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = state.copy(
            enabled = VisionConfigurationEngine.current().latencyMonitoringEnabled,
            diagnostics = RuntimeDiagnosticsRegistry.current()
        )
    }
}
/* ======
VisionLatencyMonitor Anchor
====== */

/* ========
VisionOverlayRegistry
======== */
