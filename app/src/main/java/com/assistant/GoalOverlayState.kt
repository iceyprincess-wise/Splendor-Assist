data class GoalOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().goalOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

