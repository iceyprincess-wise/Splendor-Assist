package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























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

/* ========
WingBlockEngine
======== */
