package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class BoundingBoxOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().boundingBoxOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current(),
    val visualization:RuntimeVisualizationState =
        RuntimeVisualizationRegistry.current()
)

