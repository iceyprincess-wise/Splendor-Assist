from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
PKG.mkdir(parents=True, exist_ok=True)

vision = PKG / "VisionConfiguration.kt"
tracking = PKG / "TrackingConfiguration.kt"

vision.write_text("""package com.assistant.adapter.smartassist

data class VisionConfiguration(
    val enabled:Boolean = true,
    val debugOverlayEnabled:Boolean = false,
    val boundingBoxOverlayEnabled:Boolean = false,
    val confidenceHeatmapEnabled:Boolean = false,
    val ballOverlayEnabled:Boolean = true,
    val playerOverlayEnabled:Boolean = true,
    val goalOverlayEnabled:Boolean = true,
    val sceneTrackingEnabled:Boolean = true,
    val latencyMonitoringEnabled:Boolean = true,
    val fpsMonitoringEnabled:Boolean = true,
    val diagnosticsEnabled:Boolean = true
)

object VisionConfigurationEngine {

    @Volatile
    private var configuration = VisionConfiguration()

    fun current(): VisionConfiguration = configuration

    fun update(
        block:(VisionConfiguration)->VisionConfiguration
    ){
        configuration = block(configuration)
    }

    fun reset(){
        configuration = VisionConfiguration()
    }
}
""", encoding="utf-8")

tracking.write_text("""package com.assistant.adapter.smartassist

data class TrackingConfiguration(
    val enabled:Boolean = true,
    val adaptiveTracking:Boolean = true,
    val temporalTracking:Boolean = true,
    val entityAssociation:Boolean = true,
    val predictionEnabled:Boolean = true,
    val runtimeTuningEnabled:Boolean = true,
    val trackingDiagnostics:Boolean = true,
    val frameCompensation:Boolean = true,
    val interpolationEnabled:Boolean = true
)

object TrackingConfigurationEngine {

    @Volatile
    private var configuration = TrackingConfiguration()

    fun current(): TrackingConfiguration = configuration

    fun update(
        block:(TrackingConfiguration)->TrackingConfiguration
    ){
        configuration = block(configuration)
    }

    fun reset(){
        configuration = TrackingConfiguration()
    }
}
""", encoding="utf-8")

print("CREATED:")
print(vision)
print(tracking)
