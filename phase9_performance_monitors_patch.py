from pathlib import Path

ROOT = Path.home() / "projects" / "Splendor-Assist"
PKG = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

files = {
"FPSMonitor.kt": """package com.assistant.adapter.smartassist

data class FPSMonitorState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().fpsMonitoringEnabled,
    val fps:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object FPSMonitor {

    @Volatile
    private var state = FPSMonitorState()

    fun current():FPSMonitorState = state

    fun update(fps:Float){
        state = state.copy(fps = fps)
    }

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = state.copy(
            enabled = VisionConfigurationEngine.current().fpsMonitoringEnabled,
            diagnostics = RuntimeDiagnosticsRegistry.current()
        )
    }
}
""",

"VisionLatencyMonitor.kt": """package com.assistant.adapter.smartassist

data class VisionLatencyMonitorState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().latencyMonitoringEnabled,
    val latencyMs:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object VisionLatencyMonitor {

    @Volatile
    private var state = VisionLatencyMonitorState()

    fun current():VisionLatencyMonitorState = state

    fun update(latencyMs:Float){
        state = state.copy(latencyMs = latencyMs)
    }

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = state.copy(
            enabled = VisionConfigurationEngine.current().latencyMonitoringEnabled,
            diagnostics = RuntimeDiagnosticsRegistry.current()
        )
    }
}
""",

"ConfidenceHeatmap.kt": """package com.assistant.adapter.smartassist

data class ConfidenceHeatmapState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().confidenceHeatmapEnabled,
    val confidence:Float = 0f,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object ConfidenceHeatmap {

    @Volatile
    private var state = ConfidenceHeatmapState()

    fun current():ConfidenceHeatmapState = state

    fun update(confidence:Float){
        state = state.copy(confidence = confidence)
    }

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = state.copy(
            enabled = VisionConfigurationEngine.current().confidenceHeatmapEnabled,
            diagnostics = RuntimeDiagnosticsRegistry.current()
        )
    }
}
"""
}

for name, content in files.items():
    (PKG / name).write_text(content, encoding="utf-8")
    print("CREATED:", name)
