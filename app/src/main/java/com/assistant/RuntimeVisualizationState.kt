package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class RuntimeVisualizationState(
    val diagnostics: RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current(),
    val overlayHub: RuntimeOverlayHubState =
        RuntimeOverlayHub.current(),
    val overlayRegistry: VisionOverlayRegistryState =
        VisionOverlayRegistry.current(),
    val visionOverlay: VisionDebugOverlayState =
        VisionDebugOverlay.current(),
    val runtimeTuning: RuntimeTuningState =
        RuntimeTuningPanel.current()
)

