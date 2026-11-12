package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























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

/* ========
VisionCore
======== */
