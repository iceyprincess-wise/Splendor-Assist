data class TrackingConfiguration(
    val enabled:Boolean = true,
    val adaptiveTracking:Boolean = true,
    val temporalTracking:Boolean = true,
    val entityAssociation:Boolean = true,
    val predictionEnabled:Boolean = true,
    val runtimeTuningEnabled:Boolean = true,
    val trackingDiagnostics:Boolean = true,
    val frameCompensation:Boolean = true,
    val interpolationEnabled:Boolean = true
)

