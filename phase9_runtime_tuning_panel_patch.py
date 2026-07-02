from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
PKG.mkdir(parents=True, exist_ok=True)

runtime_panel = PKG / "RuntimeTuningPanel.kt"

runtime_panel.write_text("""package com.assistant.adapter.smartassist

data class RuntimeTuningState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current()
)

object RuntimeTuningPanel {

    @Volatile
    private var state = RuntimeTuningState()

    fun current(): RuntimeTuningState = state

    fun reload() {
        state = RuntimeTuningState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current()
        )
    }

    fun updateVision(
        block:(VisionConfiguration)->VisionConfiguration
    ){
        VisionConfigurationEngine.update(block)
        reload()
    }

    fun updateTracking(
        block:(TrackingConfiguration)->TrackingConfiguration
    ){
        TrackingConfigurationEngine.update(block)
        reload()
    }

    fun reset(){
        VisionConfigurationEngine.reset()
        TrackingConfigurationEngine.reset()
        reload()
    }
}
""", encoding="utf-8")

print("CREATED:")
print(runtime_panel)
