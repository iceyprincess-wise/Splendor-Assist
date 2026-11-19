package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object VisionConfigurationEngine {

    @Volatile
    private var configuration = VisionConfiguration()

    // L15 (batch 1): the app context for persistence (set by restore()
    // in App.onCreate). The seam writes through it on every update/reset.
    @Volatile
    private var appContext: android.content.Context? = null

    fun current(): VisionConfiguration = configuration

    /**
     * L15 (batch 1): restores the persisted toggles at startup. Called
     * from App.onCreate; a missing/empty store keeps the defaults.
     * Also captures the context for write-through persistence below.
     */
    fun restore(context: android.content.Context) {
        appContext = context.applicationContext
        try {
            val saved = RegistryPersistenceSeam.load(context)
            if (saved != null) {
                synchronized(this) { configuration = saved }
            }
        } catch (_: Throwable) { }
    }

    // No68-H-D52: update()/reset() were a read-modify-write race on a
    // @Volatile var — two concurrent toggles could lose one. Synchronised;
    // current() stays a plain volatile read.
    @Synchronized
    fun update(
        block:(VisionConfiguration)->VisionConfiguration
    ){
        configuration = block(configuration)
        // L15 (batch 1): write-through persistence — a toggle survives a
        // restart. The save is async (apply) and never blocks the caller.
        persist()
    }

    @Synchronized
    fun reset(){
        configuration = VisionConfiguration()
        persist()
    }

    private fun persist() {
        val ctx = appContext ?: return
        try {
            RegistryPersistenceSeam.save(ctx, configuration)
        } catch (_: Throwable) { }
    }
}
