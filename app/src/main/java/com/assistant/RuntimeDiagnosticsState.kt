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

