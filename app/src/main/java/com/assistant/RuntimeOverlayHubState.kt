data class RuntimeOverlayHubState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val runtimeTuning: RuntimeTuningState =
        RuntimeTuningPanel.current(),
    val visionOverlay: VisionDebugOverlayState =
        VisionDebugOverlay.current(),
    val overlayRegistry: VisionOverlayRegistryState =
        VisionOverlayRegistry.current()
)

