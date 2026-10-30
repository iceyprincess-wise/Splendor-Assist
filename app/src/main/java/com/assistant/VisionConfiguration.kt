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

