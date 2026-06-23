package com.assistant.adapter.smartassist.fps

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

class MemoryStabilityOptimizer(
    private val context:Context
):ComponentCallbacks2{

    fun initialize(){
        context.registerComponentCallbacks(this)
    }

    override fun onTrimMemory(level:Int){
        if(level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW){
            Runtime.getRuntime()
        }
    }

    override fun onLowMemory(){}

    override fun onConfigurationChanged(
        newConfig:Configuration
    ){}
}
