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
/* ======
VisionDebugOverlay Anchor
====== */

/* ========
VisionLatencyMonitor
======== */
