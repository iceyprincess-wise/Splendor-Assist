object RuntimeTuningPanel {

    @Volatile
    private var state = RuntimeTuningState()

    fun current(): RuntimeTuningState = state

    fun reload() {
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
/* ======
RuntimeTuningPanel Anchor
====== */

/* ========
RuntimeVisualizationRegistry
======== */
