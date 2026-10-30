object VisionConfigurationEngine {

    @Volatile
    private var configuration = VisionConfiguration()

    fun current(): VisionConfiguration = configuration

    fun update(
        block:(VisionConfiguration)->VisionConfiguration
    ){
        configuration = block(configuration)
    }

    fun reset(){
        configuration = VisionConfiguration()
    }
}
/* ======
VisionConfiguration Anchor
====== */

/* ========
WingBlockEngine
======== */
