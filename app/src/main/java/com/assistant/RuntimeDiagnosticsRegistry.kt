package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object RuntimeDiagnosticsRegistry {

    @Volatile
    private var state = RuntimeDiagnosticsState()

    fun current(): RuntimeDiagnosticsState = state

    fun refresh() {
        // P8: one shared refresh per coordinator cycle — the first claim
        // in a cycle runs, later claims in the same cycle are skipped
        // (the coordinator's calls are sequential on one thread, so no
        // writer on that thread can change the state between claims).
        // Outside a cycle this always runs, exactly as before.
        if (!RefreshCycle.tryClaim("RuntimeDiagnosticsRegistry")) return
        RuntimeOverlayHub.refresh()
        VisionOverlayRegistry.refresh()
        VisionDebugOverlay.refresh()
        RuntimeTuningPanel.reload()

        state = RuntimeDiagnosticsState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current(),
            runtimeOverlayHub = RuntimeOverlayHub.current(),
            runtimeTuning = RuntimeTuningPanel.current(),
            visionOverlay = VisionDebugOverlay.current(),
            overlayRegistry = VisionOverlayRegistry.current()
        )
    }

    fun enableRuntimeDiagnostics() {
        RuntimeOverlayHub.enableDiagnostics()
        refresh()
    }

    fun disableRuntimeDiagnostics() {
        RuntimeOverlayHub.disableDiagnostics()
        refresh()
    }

    fun reset() {
        RuntimeOverlayHub.reset()
        refresh()
    }
}

/* ========
RuntimeOverlayHub
======== */
