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

