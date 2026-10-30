object RuntimeVisualizationRegistry {

    @Volatile
    private var state = RuntimeVisualizationState()

    fun current(): RuntimeVisualizationState = state

    fun refresh() {
        RuntimeDiagnosticsRegistry.refresh()
        RuntimeOverlayHub.refresh()
        VisionOverlayRegistry.refresh()
        VisionDebugOverlay.refresh()
        RuntimeTuningPanel.reload()

        state = RuntimeVisualizationState(
            diagnostics = RuntimeDiagnosticsRegistry.current(),
            overlayHub = RuntimeOverlayHub.current(),
            overlayRegistry = VisionOverlayRegistry.current(),
            visionOverlay = VisionDebugOverlay.current(),
            runtimeTuning = RuntimeTuningPanel.current()
        )
    }

    fun enableVisualization() {
        RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        refresh()
    }

    fun disableVisualization() {
        RuntimeDiagnosticsRegistry.disableRuntimeDiagnostics()
        refresh()
    }

    fun reset() {
        RuntimeDiagnosticsRegistry.reset()
        refresh()
    }
}
/* ======
RuntimeVisualizationRegistry Anchor
====== */

/* ========
SecurityExecutionDecoupler
======== */
