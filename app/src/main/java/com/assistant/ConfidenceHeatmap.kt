package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























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

/* ========
ConnectedComponentEngine
======== */
