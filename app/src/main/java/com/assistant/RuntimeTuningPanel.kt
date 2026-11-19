package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object RuntimeTuningPanel {

    @Volatile
    private var state = RuntimeTuningState()

    fun current(): RuntimeTuningState = state

    fun reload() {
        // P8: deduped within one coordinator refresh cycle (see
        // RefreshCycle); standalone calls always run fully.
        if (!RefreshCycle.tryClaim("RuntimeTuningPanel")) return
        state = RuntimeTuningState(
            visionConfiguration = VisionConfigurationEngine.current(),
            trackingConfiguration = TrackingConfigurationEngine.current()
        )
    }

    fun updateVision(
        block:(VisionConfiguration)->VisionConfiguration
    ){
        VisionConfigurationEngine.update(block)
        reload()
    }

    fun updateTracking(
        block:(TrackingConfiguration)->TrackingConfiguration
    ){
        TrackingConfigurationEngine.update(block)
        reload()
    }

    fun reset(){
        VisionConfigurationEngine.reset()
        TrackingConfigurationEngine.reset()
        reload()
    }
}

/* ========
RuntimeVisualizationRegistry
======== */
