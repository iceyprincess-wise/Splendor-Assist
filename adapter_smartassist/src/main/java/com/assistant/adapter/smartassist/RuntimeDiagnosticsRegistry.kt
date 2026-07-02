package com.assistant.adapter.smartassist

data class RuntimeDiagnosticsState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val runtimeOverlayHub: RuntimeOverlayHubState =
        RuntimeOverlayHub.current(),
    val runtimeTuning: RuntimeTuningState =
        RuntimeTuningPanel.current(),
    val visionOverlay: VisionDebugOverlayState =
        VisionDebugOverlay.current(),
    val overlayRegistry: VisionOverlayRegistryState =
        VisionOverlayRegistry.current()
)

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
