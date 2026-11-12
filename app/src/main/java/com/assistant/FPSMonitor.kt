package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























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

/* ========
FalseRunSequenceEngine
======== */
