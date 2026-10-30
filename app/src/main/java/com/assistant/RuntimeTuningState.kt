data class RuntimeTuningState(
    val visionConfiguration: VisionConfiguration =
        VisionConfigurationEngine.current(),
    val trackingConfiguration: TrackingConfiguration =
        TrackingConfigurationEngine.current()
)

