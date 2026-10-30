object RuntimeOverlayHub {

    @Volatile
    private var state = RuntimeOverlayHubState()

    fun current(): RuntimeOverlayHubState = state

    fun refresh() {
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
/* ======
RuntimeOverlayHub Anchor
====== */

/* ========
ShootingHabitLearningEngine
======== */
