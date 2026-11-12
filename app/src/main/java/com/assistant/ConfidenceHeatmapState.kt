package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ConfidenceHeatmapState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().confidenceHeatmapEnabled,
    val confidence:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

