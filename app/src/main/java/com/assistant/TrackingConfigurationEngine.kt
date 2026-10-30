object TrackingConfigurationEngine {

    @Volatile
    private var configuration = TrackingConfiguration()

    fun current(): TrackingConfiguration = configuration

    fun update(
        block:(TrackingConfiguration)->TrackingConfiguration
    ){
        configuration = block(configuration)
    }

    fun reset(){
        configuration = TrackingConfiguration()
    }
}
/* ======
TrackingConfiguration Anchor
====== */

/* ========
VisionCore
======== */
