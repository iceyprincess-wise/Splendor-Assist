package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object RuntimeVisualizationRegistry {

    // Item 13 (half-effect): the state snapshot is gone
    // (RuntimeVisualizationState deleted — write-only re-copy of the
    // diagnostics registry). refresh() now only runs the leaf refreshes.
    fun refresh() {
        RuntimeDiagnosticsRegistry.refresh()
        RuntimeTuningPanel.reload()
    }

    fun enableVisualization() {
        RuntimeDiagnosticsRegistry.enableRuntimeDiagnostics()
        refresh()
    }

    fun disableVisualization() {
        RuntimeDiagnosticsRegistry.disableRuntimeDiagnostics()
        refresh()
    }

    fun reset() {
        RuntimeDiagnosticsRegistry.reset()
        refresh()
    }
}

/* ========
SecurityExecutionDecoupler
======== */
