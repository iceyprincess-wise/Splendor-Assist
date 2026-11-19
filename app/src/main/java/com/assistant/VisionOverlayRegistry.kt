package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object VisionOverlayRegistry {

    @Volatile
    private var state = VisionOverlayRegistryState()

    fun current(): VisionOverlayRegistryState = state

    fun refresh() {
        // P8: deduped within one coordinator refresh cycle (see
        // RefreshCycle); standalone calls always run fully.
        if (!RefreshCycle.tryClaim("VisionOverlayRegistry")) return
        state = VisionOverlayRegistryState()
    }

    fun enableAll() {
        VisionConfigurationEngine.update {
            it.copy(
                debugOverlayEnabled = true,
                boundingBoxOverlayEnabled = true,
                ballOverlayEnabled = true,
                playerOverlayEnabled = true,
                goalOverlayEnabled = true,
                confidenceHeatmapEnabled = true
            )
        }
        VisionDebugOverlay.refresh()
        refresh()
    }

    fun disableAll() {
        VisionConfigurationEngine.update {
            it.copy(
                debugOverlayEnabled = false,
                boundingBoxOverlayEnabled = false,
                ballOverlayEnabled = false,
                playerOverlayEnabled = false,
                goalOverlayEnabled = false,
                confidenceHeatmapEnabled = false
            )
        }
        VisionDebugOverlay.refresh()
        refresh()
    }

    // ---- SELF-MASK (GAP1A): REMOVED (No62-D6) ----
    // A parallel self-mask mirror used to live here (selfRects, written
    // from OverlaySelfMask.publishView/clear and read by isSelfDrawn /
    // selfMask / selfMaskCount). All three readers had zero callers
    // repo-wide, VisionOverlayRegistryState exposes no mask field, and
    // no test or native code touched it — so the mirror was write-only
    // duplication of OverlaySelfMask.rects, kept in sync only because
    // every mutation happened to go through OverlaySelfMask. One store
    // (OverlaySelfMask) is the truth now; this section is gone rather
    // than left to desync. Do not re-add a second mask store without a
    // reader for it.

}

/* ========
PassingLane
======== */
