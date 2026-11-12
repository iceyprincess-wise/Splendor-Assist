package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object VisionDebugOverlay {

    @Volatile
    private var state = VisionDebugOverlayState()

    fun current(): VisionDebugOverlayState = state

    fun refresh() {
        state = VisionDebugOverlayState()
    }

    fun enable() {
        VisionConfigurationEngine.update {
            it.copy(debugOverlayEnabled = true)
        }
        refresh()
    }

    fun disable() {
        VisionConfigurationEngine.update {
            it.copy(debugOverlayEnabled = false)
        }
        refresh()
    }
}

/* ========
VisionLatencyMonitor
======== */
