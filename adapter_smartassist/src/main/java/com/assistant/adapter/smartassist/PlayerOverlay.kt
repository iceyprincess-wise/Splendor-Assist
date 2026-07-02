package com.assistant.adapter.smartassist

data class PlayerOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().playerOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current()
)

object PlayerOverlay {

    @Volatile
    private var state = PlayerOverlayState()

    fun current():PlayerOverlayState = state

    fun refresh(){
        RuntimeDiagnosticsRegistry.refresh()
        state = PlayerOverlayState(
            enabled =
                VisionConfigurationEngine.current().playerOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(playerOverlayEnabled = true)
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(playerOverlayEnabled = false)
        }
        refresh()
    }
}
