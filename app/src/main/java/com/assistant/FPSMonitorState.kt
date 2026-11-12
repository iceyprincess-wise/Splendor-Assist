package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class FPSMonitorState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().fpsMonitoringEnabled,
    val fps:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

