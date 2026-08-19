package com.assistant.adapter.smartassist

data class BallOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().ballOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object BallOverlay {

    @Volatile
    private var state = BallOverlayState()

    fun current():BallOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = BallOverlayState(
            enabled =
                VisionConfigurationEngine.current().ballOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(ballOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(ballOverlayEnabled = false)
        }
        refresh()
    }
}
