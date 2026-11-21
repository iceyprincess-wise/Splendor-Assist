package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























// Item 13 (half-effect): runtimeOverlayHub / visionOverlay /
// overlayRegistry are gone — their states were write-only re-copies
// (RuntimeOverlayHubState, VisionDebugOverlayState,
// VisionOverlayRegistryState deleted). The remaining fields have
// their first reader in DashboardInjector (runtime section).
data class RuntimeDiagnosticsState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val runtimeTuning: RuntimeTuningState =
        RuntimeTuningPanel.current()
)

