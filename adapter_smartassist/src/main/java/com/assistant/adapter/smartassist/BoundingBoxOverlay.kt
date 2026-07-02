package com.assistant.adapter.smartassist

data class BoundingBoxOverlayState(
    val enabled:Boolean =
        VisionConfigurationEngine.current().boundingBoxOverlayEnabled,
    val diagnostics:RuntimeDiagnosticsState =
        RuntimeDiagnosticsRegistry.current(),
    val visualization:RuntimeVisualizationState =
        RuntimeVisualizationRegistry.current()
)

object BoundingBoxOverlay {

    @Volatile
    private var state = BoundingBoxOverlayState()

    fun current():BoundingBoxOverlayState = state

    fun refresh(){
        RuntimeVisualizationRegistry.refresh()
        state = BoundingBoxOverlayState(
            enabled =
                VisionConfigurationEngine.current().boundingBoxOverlayEnabled,
            diagnostics =
                RuntimeDiagnosticsRegistry.current(),
            visualization =
                RuntimeVisualizationRegistry.current()
        )
    }

    fun enable(){
        VisionConfigurationEngine.update{
            it.copy(
                boundingBoxOverlayEnabled = true
            )
        }
        refresh()
    }

    fun disable(){
        VisionConfigurationEngine.update{
            it.copy(
                boundingBoxOverlayEnabled = false
            )
        }
        refresh()
    }
}
