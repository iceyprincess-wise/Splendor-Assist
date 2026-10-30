data class VisionOverlayRegistryState(
    val vision: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val tracking: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val overlay: VisionDebugOverlayState =
        VisionDebugOverlay.current()
)

