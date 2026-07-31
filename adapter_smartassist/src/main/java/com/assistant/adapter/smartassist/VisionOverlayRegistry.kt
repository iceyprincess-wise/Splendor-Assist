package com.assistant.adapter.smartassist

data class VisionOverlayRegistryState(
    val vision: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val tracking: TrackingConfiguration =
        TrackingConfigurationEngine.current(),
    val overlay: VisionDebugOverlayState =
        VisionDebugOverlay.current()
)

object VisionOverlayRegistry {

    @Volatile
    private var state = VisionOverlayRegistryState()

    fun current(): VisionOverlayRegistryState = state

    fun refresh() {
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

    // ---- SELF-MASK (GAP1A): overlays we drew ourselves, excluded from ingestion ----
    private val selfRects = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Rect>()

    fun publishBounds(tag: String, r: android.graphics.Rect) {
        selfRects[tag] = android.graphics.Rect(r)
    }

    fun clearBounds(tag: String) {
        selfRects.remove(tag)
    }

    fun isSelfDrawn(x: Int, y: Int): Boolean {
        if (selfRects.isEmpty()) return false
        for (r in selfRects.values) if (r.contains(x, y)) return true
        return false
    }

    fun isSelfDrawn(x: Float, y: Float): Boolean = isSelfDrawn(x.toInt(), y.toInt())

    fun selfMask(): List<android.graphics.Rect> = selfRects.values.toList()

    fun selfMaskCount(): Int = selfRects.size

}
