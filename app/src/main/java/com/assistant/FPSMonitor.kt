package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object FPSMonitor {

    @Volatile
    private var state = FPSMonitorState()

    fun current():FPSMonitorState = state

    // No68-C-D25: update() and refresh() used to replace the @Volatile
    // state unsynchronised, so an update() racing a refresh() could lose
    // the fresh fps for a cycle (refresh() copies the state it read).
    // Both writers now hold the same monitor.
    @Synchronized
    fun update(fps:Float){
        state = state.copy(fps = fps)
    }

    @Synchronized
    fun refresh(){
        // Item 13 (half-effect): the state copy is gone — `enabled` was
        // a dead config re-copy (fpsMonitoringEnabled deleted) and
        // `diagnostics` was a dead sub-snapshot (zero readers). refresh()
        // now only runs the diagnostics refresh; fps is written by
        // update() unconditionally, as before.
        RuntimeDiagnosticsRegistry.refresh()
    }
}

/* ========
FalseRunSequenceEngine
======== */
