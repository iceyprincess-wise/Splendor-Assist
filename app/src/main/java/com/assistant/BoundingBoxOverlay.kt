package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























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

/* ========
BuildUpPressEngine
======== */
/** Presses carrier's CURRENT position — arrive NOW before they play the pass. Authority=1.0. */
/* ========
BuildUpRecognitionEngine
======== */
