package com.assistant.adapter.smartassist

data class RuntimeTuningState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current()
)

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
