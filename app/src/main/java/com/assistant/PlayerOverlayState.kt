data class PlayerOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().playerOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

