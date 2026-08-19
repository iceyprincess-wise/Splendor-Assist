package com.assistant.adapter.smartassist

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


/* ============================================================
 PHASE9_RUNTIME_ACTIVATION_MARKER

 Verified activation target.

 VisionConfiguration
 TrackingConfiguration
 Runtime tuning
 Vision debug overlay

 Existing implementation preserved.
 Activation wiring to be completed without
 replacing existing architecture.

============================================================ */

