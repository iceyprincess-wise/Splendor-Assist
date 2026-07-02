#!/data/data/com.termux/files/usr/bin/env python3
from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

f = PKG / "RuntimeDiagnosticsRegistry.kt"

f.write_text("""package com.assistant.adapter.smartassist

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
""", encoding="utf-8")

print("CREATED:")
print(f)
