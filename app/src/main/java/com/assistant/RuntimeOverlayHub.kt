package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object RuntimeOverlayHub {

    @Volatile
    private var state = RuntimeOverlayHubState()

    fun current(): RuntimeOverlayHubState = state

    fun refresh() {
        // P8: deduped within one coordinator refresh cycle (see
        // RefreshCycle); standalone calls always run fully.
        if (!RefreshCycle.tryClaim("RuntimeOverlayHub")) return
        VisionDebugOverlay.refresh()
        RuntimeTuningPanel.reload()
        VisionOverlayRegistry.refresh()

        state = RuntimeOverlayHubState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current(),
            runtimeTuning = RuntimeTuningPanel.current(),
            visionOverlay = VisionDebugOverlay.current(),
            overlayRegistry = VisionOverlayRegistry.current()
        )
    }

    fun enableDiagnostics() {
        VisionOverlayRegistry.enableAll()
        refresh()
    }

    fun disableDiagnostics() {
        VisionOverlayRegistry.disableAll()
        refresh()
    }

    fun reset() {
        RuntimeTuningPanel.reset()
        refresh()
    }
}

/* ========
ShootingHabitLearningEngine
======== */
