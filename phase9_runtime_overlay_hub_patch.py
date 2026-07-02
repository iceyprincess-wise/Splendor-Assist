from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

hub = PKG / "RuntimeOverlayHub.kt"

hub.write_text("""package com.assistant.adapter.smartassist

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

object RuntimeOverlayHub {

    @Volatile
    private var state = RuntimeOverlayHubState()

    fun current(): RuntimeOverlayHubState = state

    fun refresh() {
        VisionDebugOverlay.refresh()
        RuntimeTuningPanel.reload()
        VisionOverlayRegistry.refresh()

        state = RuntimeOverlayHubState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current(),
            runtimeTuning = RuntimeTuningPanel.current(),
            visionOverlay = VisionDebugOverlay.current(),
            overlayRegistry = VisionOverlayRegistry.current()
        )
    }

    fun enableDiagnostics() {
        VisionOverlayRegistry.enableAll()
        refresh()
    }

    fun disableDiagnostics() {
        VisionOverlayRegistry.disableAll()
        refresh()
    }

    fun reset() {
        RuntimeTuningPanel.reset()
        refresh()
    }
}
""", encoding="utf-8")

print("CREATED:")
print(hub)
