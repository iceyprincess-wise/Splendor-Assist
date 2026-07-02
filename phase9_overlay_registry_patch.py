from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
PKG.mkdir(parents=True, exist_ok=True)

registry = PKG / "VisionOverlayRegistry.kt"

registry.write_text("""package com.assistant.adapter.smartassist

data class VisionOverlayRegistryState(
    val vision = VisionConfigurationEngine.current(),
    val tracking = TrackingConfigurationEngine.current(),
    val overlay = VisionDebugOverlay.current()
)

object VisionOverlayRegistry {

    @Volatile
    private var state = VisionOverlayRegistryState()

    fun current(): VisionOverlayRegistryState = state

    fun refresh() {
        state = VisionOverlayRegistryState()
    }

    fun enableAll() {
        VisionConfigurationEngine.update {
            it.copy(
                debugOverlayEnabled = true,
                boundingBoxOverlayEnabled = true,
                ballOverlayEnabled = true,
                playerOverlayEnabled = true,
                goalOverlayEnabled = true,
                confidenceHeatmapEnabled = true
            )
        }
        VisionDebugOverlay.refresh()
        refresh()
    }

    fun disableAll() {
        VisionConfigurationEngine.update {
            it.copy(
                debugOverlayEnabled = false,
                boundingBoxOverlayEnabled = false,
                ballOverlayEnabled = false,
                playerOverlayEnabled = false,
                goalOverlayEnabled = false,
                confidenceHeatmapEnabled = false
            )
        }
        VisionDebugOverlay.refresh()
        refresh()
    }
}
""", encoding="utf-8")

print("CREATED:")
print(registry)
