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
        // Item 13 (half-effect): the state copy is gone — `enabled` was
        // a dead config re-copy (confidenceHeatmapEnabled deleted) and
        // `diagnostics` was a dead sub-snapshot (zero readers). refresh()
        // now only runs the diagnostics refresh; confidence is written by
        // update() unconditionally, as before.
        RuntimeDiagnosticsRegistry.refresh()
    }
}

/* ========
ConnectedComponentEngine
======== */
