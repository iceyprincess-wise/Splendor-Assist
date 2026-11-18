package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























object VisionConfigurationEngine {

    @Volatile
    private var configuration = VisionConfiguration()

    fun current(): VisionConfiguration = configuration

    // No68-H-D52: update()/reset() were a read-modify-write race on a
    // @Volatile var — two concurrent toggles could lose one. Synchronised;
    // current() stays a plain volatile read.
    @Synchronized
    fun update(
        block:(VisionConfiguration)->VisionConfiguration
    ){
        configuration = block(configuration)
    }

    @Synchronized
    fun reset(){
        configuration = VisionConfiguration()
    }
}
