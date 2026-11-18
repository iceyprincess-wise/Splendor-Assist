package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class VisionConfiguration(
    val enabled:Boolean = true,
    val debugOverlayEnabled:Boolean = false,
    val boundingBoxOverlayEnabled:Boolean = false,
    val confidenceHeatmapEnabled:Boolean = false,
    val ballOverlayEnabled:Boolean = true,
    val playerOverlayEnabled:Boolean = true,
    val goalOverlayEnabled:Boolean = true,
    // No68-H-D42: the blob luminance threshold, previously hardcoded 0.50f
    // inside VisionPreprocessor.process() — now a config value (default
    // 0.50f, so default behaviour is unchanged).
    val blobThreshold:Float = 0.50f,
    val latencyMonitoringEnabled:Boolean = true,
    val fpsMonitoringEnabled:Boolean = true
    // No68-H-D50: sceneTrackingEnabled + diagnosticsEnabled deleted —
    // zero readers and zero writers repo-wide (no overlay, monitor,
    // registry or engine ever read them), no positional constructions
    // and no copy() uses them, so deletion is source-safe.
) {
    init {
        require(blobThreshold in 0f..1f) { "blobThreshold must be in 0..1, was $blobThreshold" }
    }
}

