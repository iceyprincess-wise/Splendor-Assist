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
        RuntimeTuningPanel.reload()

        state = RuntimeDiagnosticsState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current(),
            runtimeTuning = RuntimeTuningPanel.current()
        )
    }

    // Item 13 (half-effect): the hub calls are gone — the overlay
    // toggles they enabled were deleted (no draw code ever read them),
    // so enable/disable now just refresh the live snapshot.
    fun enableRuntimeDiagnostics() {
        refresh()
    }

    fun disableRuntimeDiagnostics() {
        refresh()
    }

    fun reset() {
        RuntimeTuningPanel.reset()
        refresh()
    }
}

/* ========
RuntimeOverlayHub
======== */
