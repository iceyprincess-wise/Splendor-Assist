package com.assistant.adapter.smartassist

data class GoalOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().goalOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object GoalOverlay {

    @Volatile
    private var state = GoalOverlayState()

    fun current():GoalOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = GoalOverlayState(
            enabled =
                VisionConfigurationEngine.current().goalOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(goalOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(goalOverlayEnabled = false)
        }
        refresh()
    }
}
