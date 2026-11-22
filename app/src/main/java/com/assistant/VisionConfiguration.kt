package com.assistant

data class VisionConfiguration(
    val enabled:Boolean = true,
    // No68-H-D42: the blob luminance threshold, previously hardcoded 0.50f
    // inside VisionPreprocessor.process() — now a config value (default
    // 0.50f, so default behaviour is unchanged).
    val blobThreshold:Float = 0.50f,
    // Queue item 21 (v3 TFLite detector): the trained-detection gate.
    // Default TRUE because file presence is the real opt-in — with no
    // model file placed the engine is MISSING_MODEL and behaviour is
    // identical to today; the detector only activates after Splendor
    // himself places the file (the test he authorized). Read in
    // VisionCore.process() around the trained ball/keeper/players
    // fusion; persisted via VisionConfigurationEngine.update/persist.
    // Programmatic surface: VisionConfigurationEngine.update { it.copy(
    // trainedDetectionEnabled = false) } (or RuntimeTuningPanel.
    // updateVision). No rendered toggle UI exists today — the Mod Menu /
    // smartassist room never renders individual VisionConfiguration
    // fields (updateVision has no UI caller) — so this is open until a
    // UI surface is built for it.
    val trainedDetectionEnabled:Boolean = true
    // No68-H-D50: sceneTrackingEnabled + diagnosticsEnabled deleted —
    // zero readers and zero writers repo-wide (no overlay, monitor,
    // registry or engine ever read them), no positional constructions
    // and no copy() uses them, so deletion is source-safe.
    // Item 13 (half-effect): the same deletion for the 8 overlay /
    // monitor toggles (debugOverlayEnabled, boundingBoxOverlayEnabled,
    // confidenceHeatmapEnabled, ballOverlayEnabled, playerOverlayEnabled,
    // goalOverlayEnabled, latencyMonitoringEnabled, fpsMonitoringEnabled)
    // — no draw code, monitor or engine ever read them; the only writers
    // were enableAll/disableAll and the persistence seam, both removed
    // with the fields. Behaviour is unchanged: the overlays were never
    // drawn and the monitors update unconditionally.
) {
    init {
        require(blobThreshold in 0f..1f) { "blobThreshold must be in 0..1, was $blobThreshold" }
    }
}
