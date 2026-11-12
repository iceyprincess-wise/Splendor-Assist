package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class VisionLatencyMonitorState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().latencyMonitoringEnabled,
    val latencyMs:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

