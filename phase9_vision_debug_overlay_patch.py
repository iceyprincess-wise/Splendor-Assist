from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
PKG.mkdir(parents=True, exist_ok=True)

overlay = PKG / "VisionDebugOverlay.kt"

overlay.write_text("""package com.assistant.adapter.smartassist

data class VisionDebugOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().debugOverlayEnabled,
    val boundingBoxes:Boolean =
        VisionConfigurationEngine.current().boundingBoxOverlayEnabled,
    val ballOverlay:Boolean =
        VisionConfigurationEngine.current().ballOverlayEnabled,
    val playerOverlay:Boolean =
        VisionConfigurationEngine.current().playerOverlayEnabled,
    val goalOverlay:Boolean =
        VisionConfigurationEngine.current().goalOverlayEnabled,
    val confidenceHeatmap:Boolean =
        VisionConfigurationEngine.current().confidenceHeatmapEnabled
)

object VisionDebugOverlay {

    @Volatile
    private var state = VisionDebugOverlayState()

    fun current(): VisionDebugOverlayState = state

    fun refresh() {
        state = VisionDebugOverlayState()
    }

    fun enable() {
        VisionConfigurationEngine.update {
            it.copy(debugOverlayEnabled = true)
        }
        refresh()
    }

    fun disable() {
        VisionConfigurationEngine.update {
            it.copy(debugOverlayEnabled = false)
        }
        refresh()
    }
}
""", encoding="utf-8")

print("CREATED:")
print(overlay)
