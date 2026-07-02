package com.assistant.adapter.smartassist

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
