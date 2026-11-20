package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object VisionDebugOverlay {

    @Volatile
    private var state = VisionDebugOverlayState()

    fun current(): VisionDebugOverlayState = state

    fun refresh() {
        // P8: deduped within one coordinator refresh cycle (see
        // RefreshCycle); standalone calls always run fully.
        if (!RefreshCycle.tryClaim("VisionDebugOverlay")) return
        state = VisionDebugOverlayState()
    }

}

/* ========
VisionLatencyMonitor
======== */
