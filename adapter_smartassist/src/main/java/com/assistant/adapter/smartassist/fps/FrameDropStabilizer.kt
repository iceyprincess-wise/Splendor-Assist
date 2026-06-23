package com.assistant.adapter.smartassist.fps

import android.view.Choreographer

class FrameDropStabilizer {

    private var lastFrame=0L

    private val threshold=11111111L

    fun start(
        onDrop:()->Unit
    ){
        Choreographer.getInstance()
            .postFrameCallback(
                object:Choreographer.FrameCallback{

                    override fun doFrame(
                        frameTimeNanos:Long
                    ){

                        if(lastFrame!=0L){

                            val delta=
                                frameTimeNanos-lastFrame

                            if(delta>threshold){
                                onDrop.invoke()
                            }
                        }

                        lastFrame=frameTimeNanos

                        Choreographer
                            .getInstance()
                            .postFrameCallback(this)
                    }
                }
            )
    }
}
