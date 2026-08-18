package com.assistant.adapter.smartassist

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
