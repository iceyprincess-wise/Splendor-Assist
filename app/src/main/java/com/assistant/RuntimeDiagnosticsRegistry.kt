object RuntimeDiagnosticsRegistry {

    @Volatile
    private var state = RuntimeDiagnosticsState()

    fun current(): RuntimeDiagnosticsState = state

    fun refresh() {
        RuntimeOverlayHub.refresh()
        VisionOverlayRegistry.refresh()
        VisionDebugOverlay.refresh()
        RuntimeTuningPanel.reload()

        state = RuntimeDiagnosticsState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current(),
            runtimeOverlayHub = RuntimeOverlayHub.current(),
            runtimeTuning = RuntimeTuningPanel.current(),
            visionOverlay = VisionDebugOverlay.current(),
            overlayRegistry = VisionOverlayRegistry.current()
        )
    }

    fun enableRuntimeDiagnostics() {
        RuntimeOverlayHub.enableDiagnostics()
        refresh()
    }

    fun disableRuntimeDiagnostics() {
        RuntimeOverlayHub.disableDiagnostics()
        refresh()
    }

    fun reset() {
        RuntimeOverlayHub.reset()
        refresh()
    }
}
/* ======
RuntimeDiagnosticsRegistry Anchor
====== */

/* ========
RuntimeOverlayHub
======== */
